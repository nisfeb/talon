package io.nisfeb.talon.ai

import kotlinx.coroutines.CancellationException
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
        assertEquals(DictationRun.NO_SPEECH_TO_TEXT, DictationRun.blocker(cfg(speech = false), hasShip = true))
    }

    @Test
    fun `whisper's segments become one line`() {
        val text = DictationRun.text(listOf(CallTranscribe.Segment(0, " buy milk "), CallTranscribe.Segment(900, "and eggs")))
        assertEquals("buy milk and eggs", text)
        assertEquals("", DictationRun.text(emptyList()))
    }
}
