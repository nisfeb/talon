package io.nisfeb.talon.ai

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.nisfeb.talon.call.WavFile
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.orrery.OrreryRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a dictation sends: the audio to the transcription key's endpoint, and the words to orrery. */
class DictationWireTest {

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun `the recording goes to the transcription endpoint as 16 kHz mono wav`() = runBlocking {
        var url = ""
        var auth: String? = null
        var body = ByteArray(0)
        val http = HttpClient(MockEngine { req ->
            url = req.url.toString()
            auth = req.headers[HttpHeaders.Authorization]
            body = req.body.toByteArray()
            respond("""{"text":"buy milk","segments":[{"start":0.0,"text":" buy milk"},{"start":1.2,"text":"and eggs"}]}""", HttpStatusCode.OK, json)
        })
        // An Anthropic chat key and the dedicated speech key in AI settings.
        val cfg = AiSettings.Config(provider = AiSettings.Provider.Anthropic, apiKey = "sk-ant", model = "claude", sttApiKey = "sk-whisper")
        val stt = CallRecordingPublisher.sttFrom(cfg)!!
        val wav = WavFile.encode(ByteArray(3_200), DictationRun.RATE)

        val out = DictationRun(transcribe = DictationRun.whisper(http, stt), act = { "Filed: $it" }).fromAudio(wav)

        assertEquals(DictationOutcome.Done("buy milk and eggs", "Filed: buy milk and eggs"), out)
        assertEquals("https://api.openai.com/v1/audio/transcriptions", url)
        assertEquals("Bearer sk-whisper", auth)
        val text = body.decodeToString()
        // Each part: its name, its own headers, a blank line, the value.
        fun part(name: String) = Regex("name=\"$name\"[^\\r]*\\r\\n(?:[^\\r]+\\r\\n)*\\r\\n([^\\r]*)\\r\\n").find(text)?.groupValues?.get(1)
        assertEquals("whisper-1", part("model"), text.take(400))
        assertEquals("verbose_json", part("response_format"))
        assertTrue("Content-Type: audio/wav" in text)
        // The wav itself, header and all: mono, at 16000 Hz.
        val at = (0..body.size - 4).first { body[it] == 'R'.code.toByte() && body.copyOfRange(it, it + 4).decodeToString() == "RIFF" }
        assertTrue(body.copyOfRange(at, at + wav.size).contentEquals(wav))
        assertEquals(1, wav[22].toInt())
        assertEquals(16_000, (wav[24].toInt() and 0xFF) or ((wav[25].toInt() and 0xFF) shl 8) or ((wav[26].toInt() and 0xFF) shl 16))
    }

    @Test
    fun `a refused key is a failed transcription with the provider's reason`() = runBlocking {
        val http = HttpClient(MockEngine { respond("""{"error":"bad key"}""", HttpStatusCode.Unauthorized, json) })
        val stt = CallRecordingPublisher.Stt("https://api.openai.com/v1/audio/transcriptions", "sk-x", "whisper-1")
        val out = DictationRun(transcribe = DictationRun.whisper(http, stt), act = { "x" }).fromAudio(ByteArray(44))
        assertTrue(out is DictationOutcome.TranscribeFailed && out.why == "The transcription service said no (401)" && "bad key" in out.details, out.toString())
    }

    @Test
    fun `a spoken note lands on orrery's read route, with nothing left attached`() = runBlocking {
        val dir = createTempDirectory(prefix = "talon-dictation-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(dir, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val scope = CoroutineScope(SupervisorJob())
        val asked = CopyOnWriteArrayList<Pair<String, String>>()
        val http = HttpClient(MockEngine { req ->
            asked += "${req.method.value} ${req.url.encodedPath}" to req.body.toByteArray().decodeToString()
            respond("""{"ok":true,"id":"r7"}""", HttpStatusCode.OK, json)
        })
        try {
            val repo = OrreryRepo(http, scope, db, "test", bareClient = http)
            var turn = 0
            val summary = repo.headless("https://ship.test", "~zod") {
                DictationAgent(
                    completer = { _, _, tools ->
                        if (turn++ == 0) {
                            assertTrue(tools.none { it.name == "orrery_configure" }, "a locked phone was offered orrery's settings")
                            AgentTurn.Calls(null, listOf(ToolCall("1", "orrery_file_text", buildJsonObject {
                                put("text", "Linus has a dentist appointment to book")
                                put("title", "Dentist for Linus")
                            })))
                        } else {
                            AgentTurn.Final("Handed the dentist note to orrery.")
                        }
                    },
                    tools = DictationAgent.toolsFor(orreryTools(repo), locked = true),
                ).run("Linus has a dentist appointment to book")
            }
            assertEquals("Handed the dentist note to orrery.", summary)
            val (route, body) = asked.single()
            assertEquals("POST /apps/orrery/api/read", route)
            val sent = Json.parseToJsonElement(body).jsonObject
            assertEquals("Linus has a dentist appointment to book", sent["text"]?.jsonPrimitive?.content)
            assertEquals("Dentist for Linus", sent["title"]?.jsonPrimitive?.content)
            assertEquals("talon", sent["source"]?.jsonObject?.get("kind")?.jsonPrimitive?.content)
            // Let go after, and never the app's own: no loop, no beacon, nothing attached.
            assertNull(repo.shipUrl)
            // The app's slot is process-wide and other tests leave theirs
            // in it, so what is ours to pin is that this repo never took it.
            assertTrue(OrreryRepo.attached() !== repo)
        } finally {
            scope.cancel()
            db.close()
            dir.deleteRecursively()
        }
    }
}
