package io.nisfeb.talon.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** What a dictation sends Armillary, under a lease and through the proxy, and what it says when Armillary refuses. */
class ArmillaryTranscribeTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val audio = ByteArray(300) { (it % 251).toByte() }

    private class Seen { var url = ""; var auth: String? = null; var type: String? = null; var body = "" }

    private fun ship(seen: Seen, status: HttpStatusCode = HttpStatusCode.OK, answer: String = """{"text":" Buy milk. ","usage":{"cost":0.0001}}""") =
        HttpClient(MockEngine { req ->
            seen.url = req.url.toString()
            seen.auth = req.headers[HttpHeaders.Authorization]
            seen.type = req.body.contentType?.toString()
            seen.body = req.body.toByteArray().decodeToString()
            respond(answer, status, json)
        })

    @OptIn(ExperimentalEncodingApi::class)
    private fun check(seen: Seen, url: String, key: String) {
        assertEquals(url, seen.url)
        assertEquals("Bearer $key", seen.auth)
        assertTrue(seen.type!!.startsWith("application/json"), seen.type)
        val b = Json.parseToJsonElement(seen.body).jsonObject
        assertEquals("openai/whisper-large-v3-turbo", b["model"]!!.jsonPrimitive.content)
        assertEquals("m4a", b["input_audio"]!!.jsonObject["format"]!!.jsonPrimitive.content)
        assertEquals(audio.toList(), Base64.decode(b["input_audio"]!!.jsonObject["data"]!!.jsonPrimitive.content).toList())
        assertEquals("true", b["provider"]!!.jsonObject["zdr"]!!.jsonPrimitive.content)
    }

    @Test
    fun `under a lease it goes to OpenRouter with the lease key`() = runBlocking {
        val seen = Seen()
        val lease = Hearing.Armillary("https://openrouter.ai/api/v1", "sk-or-lease", "openai/whisper-large-v3-turbo")
        assertEquals("Buy milk.", ArmillaryTranscribe.transcribe(ship(seen), lease, audio, "m4a", "en"))
        check(seen, "https://openrouter.ai/api/v1/audio/transcriptions", "sk-or-lease")
    }

    @Test
    fun `through the proxy it goes to the vendor's ship with the armillary key`() = runBlocking {
        val seen = Seen()
        val proxy = Hearing.Armillary("https://vendor.example/apps/armillary/v1/", "arm-key", "openai/whisper-large-v3-turbo")
        assertEquals("Buy milk.", ArmillaryTranscribe.transcribe(ship(seen), proxy, audio, "m4a"))
        check(seen, "https://vendor.example/apps/armillary/v1/audio/transcriptions", "arm-key")
    }

    private fun refused(status: Int, message: String): DictationOutcome = runBlocking {
        val http = ship(Seen(), HttpStatusCode.fromValue(status), """{"error":{"message":"$message"}}""")
        val hearing = Hearing.Armillary("https://vendor.example/apps/armillary/v1", "arm-key", "openai/whisper-large-v3-turbo")
        DictationRun(transcribe = { ArmillaryTranscribe.transcribe(http, hearing, it, "m4a") }, act = { "x" }).fromAudio(audio)
    }

    @Test
    fun `an empty balance says so in Armillary's words, and the audio is kept`() {
        val out = refused(402, "balance: empty")
        assertIs<DictationOutcome.TranscribeFailed>(out)
        assertEquals("Your Armillary balance is empty. Top up under Settings, AI", out.why)
        assertTrue("balance: empty" in out.details, "the vendor's own words go in the details")
    }

    @Test
    fun `each refusal is said in words, never the raw reply`() {
        val said = mapOf(
            401 to "Armillary turned this phone away: its lease is off or at its limit. Check Settings, AI",
            413 to "The recording was too long to send",
            404 to "The speech model is not on offer right now. Check Settings, AI",
            400 to "The speech model is not on offer right now. Check Settings, AI",
            504 to "The speech service is busy. Try again in a moment",
        )
        said.forEach { (status, line) ->
            val out = refused(status, "model: not offered")
            assertIs<DictationOutcome.TranscribeFailed>(out)
            assertEquals(line, out.why, "status $status")
        }
    }
}
