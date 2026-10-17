package io.nisfeb.talon.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.nisfeb.talon.call.RecordedCall
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A recorded call transcribed through Armillary: each speaker's stretches
 * of speech go to the transcription route as WAV, one request each, and
 * come back as one conversation in the order it was spoken.
 */
@OptIn(ExperimentalEncodingApi::class)
class CallTranscriptWireTest {
    private val rate = 16_000
    private val hearing = Hearing("https://openrouter.ai/api/v1", "sk-or-lease", "openai/whisper-large-v3-turbo")

    private fun sound(ms: Int, amp: Int): ByteArray {
        val n = rate * ms / 1000
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = if (amp == 0) (if (i % 2 == 0) 20 else -20) else (amp * sin(2 * PI * 220 * i / rate)).toInt()
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = (v shr 8).toByte()
        }
        return out
    }

    @Test
    fun `two speakers come back as one conversation, in the order it was spoken`() = runBlocking {
        val bodies = CopyOnWriteArrayList<String>()
        val urls = CopyOnWriteArrayList<String>()
        var n = 0
        val http = HttpClient(MockEngine { req ->
            urls += req.url.toString()
            bodies += req.body.toByteArray().decodeToString()
            n++
            respond("""{"text":" line $n ","usage":{"seconds":1}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        // ~bus speaks at 1 s and at 6 s; ~nec answers at 3.5 s.
        val bus = sound(1_000, 0) + sound(1_500, 4_000) + sound(3_500, 0) + sound(1_000, 4_000) + sound(1_000, 0)
        val nec = sound(3_500, 0) + sound(1_500, 4_000) + sound(3_000, 0)
        val call = RecordedCall(clips = mapOf("~bus" to bus, "~nec" to nec), sampleRate = rate)

        val t = CallRecordingPublisher.transcribeAll(http, hearing, call) { it }

        assertEquals(3, bodies.size)
        assertTrue(urls.all { it == "https://openrouter.ai/api/v1/audio/transcriptions" })
        bodies.forEach { b ->
            val o = Json.parseToJsonElement(b).jsonObject
            assertEquals("openai/whisper-large-v3-turbo", o["model"]!!.jsonPrimitive.content)
            val audio = o["input_audio"]!!.jsonObject
            assertEquals("wav", audio["format"]!!.jsonPrimitive.content)
            val wav = Base64.decode(audio["data"]!!.jsonPrimitive.content)
            assertEquals("RIFF", wav.copyOfRange(0, 4).decodeToString())
            assertTrue(wav.size < 12 * 1024 * 1024, "under the vendor's cap once base64")
        }
        assertEquals(listOf("~bus", "~nec", "~bus"), t.utterances.map { it.speaker })
        // Each put where its stretch starts, 200 ms before the voice.
        val at = t.utterances.map { it.startMs }
        assertTrue(at[0] in 780L..820L && at[1] in 3_280L..3_320L && at[2] in 5_780L..5_820L, at.toString())
        // ~bus's first stretch: 1.5 s of voice and 200 ms each side, as 16 kHz WAV.
        val first = Base64.decode(Json.parseToJsonElement(bodies.first()).jsonObject["input_audio"]!!.jsonObject["data"]!!.jsonPrimitive.content)
        assertTrue((first.size - 44) / 32 in 1_860..1_960, "${(first.size - 44) / 32} ms sent")
        assertTrue(t.utterances.zipWithNext().all { (a, b) -> a.startMs <= b.startMs })
        assertTrue(t.utterances.all { it.text.startsWith("line ") }, "trimmed words: ${t.utterances}")
        assertTrue(t.failed.isEmpty())
    }
}
