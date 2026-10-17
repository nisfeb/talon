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

/** What a dictation sends: the audio to Armillary's transcription route, and the words to orrery. */
class DictationWireTest {

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private val hearing = Hearing("https://openrouter.ai/api/v1", "sk-or-lease", "openai/whisper-large-v3-turbo")

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    @Test
    fun `the recording goes to Armillary's transcription route as 16 kHz mono audio`() = runBlocking {
        var url = ""
        var auth: String? = null
        var body = ""
        val http = HttpClient(MockEngine { req ->
            url = req.url.toString()
            auth = req.headers[HttpHeaders.Authorization]
            body = req.body.toByteArray().decodeToString()
            respond("""{"text":" buy milk and eggs ","usage":{"seconds":2}}""", HttpStatusCode.OK, json)
        })
        val wav = WavFile.encode(ByteArray(3_200), DictationRun.RATE)

        val out = DictationRun(transcribe = { ArmillaryTranscribe.transcribe(http, hearing, it, "wav") }, act = { "Filed: $it" }).fromAudio(wav)

        assertEquals(DictationOutcome.Done("buy milk and eggs", "Filed: buy milk and eggs"), out)
        assertEquals("https://openrouter.ai/api/v1/audio/transcriptions", url)
        assertEquals("Bearer sk-or-lease", auth)
        val sent = Json.parseToJsonElement(body).jsonObject
        assertEquals("openai/whisper-large-v3-turbo", sent["model"]!!.jsonPrimitive.content)
        val audio = sent["input_audio"]!!.jsonObject
        assertEquals("wav", audio["format"]!!.jsonPrimitive.content)
        // The wav itself, header and all: mono, at 16000 Hz.
        val got = kotlin.io.encoding.Base64.decode(audio["data"]!!.jsonPrimitive.content)
        assertTrue(got.contentEquals(wav))
        assertEquals(1, got[22].toInt())
        assertEquals(16_000, (got[24].toInt() and 0xFF) or ((got[25].toInt() and 0xFF) shl 8) or ((got[26].toInt() and 0xFF) shl 16))
    }

    @Test
    fun `a refused lease is a failed transcription said in words`() = runBlocking {
        val http = HttpClient(MockEngine { respond("""{"error":{"message":"key disabled"}}""", HttpStatusCode.Unauthorized, json) })
        val out = DictationRun(transcribe = { ArmillaryTranscribe.transcribe(http, hearing, it, "wav") }, act = { "x" }).fromAudio(ByteArray(44))
        assertTrue(out is DictationOutcome.TranscribeFailed && out.why.startsWith("Armillary turned this phone away") && "key disabled" in out.details, out.toString())
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
            // Said from the result: no second model call to word it.
            assertEquals("Filed in orrery: Dentist for Linus.", summary)
            assertEquals(1, turn, "the model was asked to word a note that was filed")
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
