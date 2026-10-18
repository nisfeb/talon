package io.nisfeb.talon.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DictationRunTest {
    private val audio = byteArrayOf(1, 2, 3)

    @Test
    fun `heard and acted on, the line and the words come back`() = runBlocking {
        var given: ByteArray? = null
        var acted: String? = null
        val out = DictationRun(
            transcribe = { given = it; "  buy milk  " },
            act = { acted = it; "Filed it in orrery." },
        ).fromAudio(audio)
        assertEquals(DictationOutcome.Done("buy milk", "Filed it in orrery."), out)
        assertTrue(given.contentEquals(audio))
        assertEquals("buy milk", acted)
    }

    @Test
    fun `an agent with nothing to say still says it is done`() = runBlocking {
        val out = DictationRun(transcribe = { "x" }, act = { " " }).fromAudio(audio)
        assertEquals(DictationOutcome.Done("x", "Done."), out)
    }

    @Test
    fun `no words heard runs nothing`() = runBlocking {
        var acted = false
        val out = DictationRun(transcribe = { " \n" }, act = { acted = true; "x" }).fromAudio(audio)
        assertEquals(DictationOutcome.NothingHeard, out)
        assertTrue(!acted, "the agent ran on silence")
    }

    @Test
    fun `no transcription key says so and sends nothing`() = runBlocking {
        var acted = false
        val out = DictationRun(transcribe = null, act = { acted = true; "x" }).fromAudio(audio)
        assertEquals(DictationOutcome.NeedsSetup(DictationRun.NO_SPEECH_TO_TEXT), out)
        assertTrue(!acted)
    }

    @Test
    fun `no model spends nothing on a transcription it cannot use`() = runBlocking {
        var transcribed = false
        val out = DictationRun(transcribe = { transcribed = true; "x" }, act = null).fromAudio(audio)
        assertEquals(DictationOutcome.NeedsSetup(DictationRun.NO_MODEL), out)
        assertTrue(!transcribed, "the audio was sent to a paid provider for nothing")
    }

    @Test
    fun `a transcription that fails says why in words, the error whole kept`() = runBlocking {
        val out = DictationRun(
            transcribe = { error("transcription failed: 401 {\"error\":\"bad key\"}") },
            act = { "x" },
        ).fromAudio(audio)
        assertIs<DictationOutcome.TranscribeFailed>(out)
        assertEquals("The transcription service said no (401)", out.why)
        assertTrue("bad key" in out.details && "IllegalStateException" in out.details)
    }

    @Test
    fun `a run that fails keeps the words`() = runBlocking {
        val out = DictationRun(
            transcribe = { "call the dentist" },
            act = { throw IllegalStateException("the model did not answer") },
        ).fromAudio(audio)
        assertIs<DictationOutcome.RunFailed>(out)
        assertEquals("call the dentist", out.transcript)
        assertEquals("the model did not answer", out.why)
    }

    @Test
    fun `no network is said as no network, never as a host it could not resolve`() {
        val e = RuntimeException("wrapped", kotlinx.io.IOException("Unable to resolve host \"api.openai.com\": No address associated with hostname"))
        assertEquals("No network, or the service did not answer", DictationRun.why(e))
        assertTrue("api.openai.com" in io.nisfeb.talon.util.errorDetailsOf(e), "the host is in the details")
    }

    @Test
    fun `an exception's own words reach the line only when they read as a sentence`() {
        assertEquals("It took longer than 4 minutes", DictationRun.why(IllegalStateException("It took longer than 4 minutes")))
        assertEquals("Something went wrong", DictationRun.why(IllegalArgumentException()))
        assertEquals("Something went wrong", DictationRun.why(IllegalStateException("kotlinx.serialization.SerializationException: {\"x\":1}")))
    }

    @Test
    fun `a cancelled dictation is cancelled, not a failure to report`() = runBlocking {
        assertFailsWith<CancellationException> {
            DictationRun(transcribe = { throw CancellationException("gone") }, act = { "x" }).fromAudio(audio)
        }
        assertFailsWith<CancellationException> {
            DictationRun(transcribe = { "x" }, act = { throw CancellationException("gone") }).fromAudio(audio)
        }
        Unit
    }

    @Test
    fun `retrying the words runs them again without a transcription`() = runBlocking {
        val out = DictationRun(transcribe = null, act = { "Done: $it" }).fromText("  call the dentist ")
        assertEquals(DictationOutcome.Done("call the dentist", "Done: call the dentist"), out)
        assertEquals(DictationOutcome.NothingHeard, DictationRun(null, { "x" }).fromText("  "))
        assertIs<DictationOutcome.NeedsSetup>(DictationRun(null, null).fromText("x"))
        Unit
    }

    private fun cfg(speech: Boolean = true, agent: Boolean = true) = paidSettings(
        assistant = agent,
        row = armillaryRow(suggested = mapOf("default" to "anthropic/claude-sonnet-5.5") + if (speech) mapOf(TRANSCRIPTION to "openai/whisper-large-v3-turbo") else emptyMap()),
    )

    @Test
    fun `what stops a dictation before it records`() {
        assertEquals(DictationRun.NO_SHIP, DictationRun.blocker(cfg(), hasShip = false))
        assertNull(DictationRun.blocker(cfg(), hasShip = true))
        assertEquals(DictationRun.NO_MODEL, DictationRun.blocker(cfg(agent = false), hasShip = true))
        assertEquals(DictationRun.NO_ARMILLARY, DictationRun.blocker(paidSettings(key = ""), hasShip = true))
        assertEquals(DictationRun.NO_SPEECH_TO_TEXT, DictationRun.blocker(cfg(speech = false), hasShip = true))
    }

    // ─── a transcription the owner waits on (B2-11) ───────────────

    @Test
    fun `a transcription out of time says so calmly, and only a transcription's does`() = runBlocking {
        val late = HttpRequestTimeoutException("https://armillary.test/v1/audio/transcriptions", 180_000)
        for (e in listOf(late, io.ktor.client.network.sockets.SocketTimeoutException("read timed out"), RuntimeException("wrapped", late))) {
            val out = DictationRun(transcribe = { throw e }, act = { "x" }).fromAudio(audio)
            assertIs<DictationOutcome.TranscribeFailed>(out)
            assertEquals(DictationRun.SPEECH_TOO_SLOW, out.why, "$e")
            assertTrue("180000" in out.details || "timed out" in out.details, "the error whole is kept")
        }
        // A model call out of time is no speech service.
        val run = DictationRun(transcribe = { "call the dentist" }, act = { throw late }).fromAudio(audio)
        assertIs<DictationOutcome.RunFailed>(run)
        assertEquals("No network, or the service did not answer", run.why)
        // Nor is a refusal or a dead network.
        assertEquals("No network, or the service did not answer", (DictationRun(transcribe = { throw kotlinx.io.IOException("reset") }, act = { "x" }).fromAudio(audio) as DictationOutcome.TranscribeFailed).why)
    }

    @Test
    fun `a spoken transcription gives up at its own wait, on real time, and a recording keeps eleven minutes`() = runBlocking {
        val waits = mutableListOf<Long?>()
        val http = HttpClient(MockEngine { req ->
            val t = req.getCapabilityOrNull(HttpTimeoutCapability)
            waits += t?.requestTimeoutMillis
            waits += t?.socketTimeoutMillis
            delay(10_000)
            respond("""{"text":"too late"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(HttpTimeout) }
        val hearing = Hearing("https://armillary.test/v1", "k", "openai/whisper-large-v3-turbo")
        val started = kotlin.time.TimeSource.Monotonic.markNow()
        val out = DictationRun(
            transcribe = { ArmillaryTranscribe.transcribe(http, hearing, it, "wav", timeoutMs = 200) },
            act = { error("acted on words that never came") },
        ).fromAudio(audio)
        assertIs<DictationOutcome.TranscribeFailed>(out)
        assertEquals(DictationRun.SPEECH_TOO_SLOW, out.why)
        assertTrue(started.elapsedNow().inWholeMilliseconds < 5_000, "it waited for the answer anyway")
        assertEquals(listOf<Long?>(200, 200), waits, "the request and its socket")
        assertEquals(3 * 60_000L, ArmillaryTranscribe.SPOKEN_WAIT_MS)
        assertEquals(11 * 60_000L, ARMILLARY_WAIT_MS, "a call recording waits as long as the proxy may")
    }
}
