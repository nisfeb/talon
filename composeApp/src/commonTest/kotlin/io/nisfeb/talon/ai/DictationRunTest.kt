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
    fun `a transcription that fails says why`() = runBlocking {
        val out = DictationRun(
            transcribe = { error("transcription failed: 401 bad key") },
            act = { "x" },
        ).fromAudio(audio)
        assertEquals(DictationOutcome.TranscribeFailed("transcription failed: 401 bad key"), out)
    }

    @Test
    fun `a run that fails keeps the words`() = runBlocking {
        val out = DictationRun(
            transcribe = { "call the dentist" },
            act = { throw IllegalStateException("the model did not answer") },
        ).fromAudio(audio)
        assertEquals(DictationOutcome.RunFailed("call the dentist", "the model did not answer"), out)
    }

    @Test
    fun `a failure with no message is named by its kind`() = runBlocking {
        val out = DictationRun(transcribe = { "x" }, act = { throw IllegalArgumentException() }).fromAudio(audio)
        assertEquals(DictationOutcome.RunFailed("x", "IllegalArgumentException"), out)
        val heard = DictationRun(transcribe = { throw IllegalArgumentException() }, act = { "x" }).fromAudio(audio)
        assertEquals(DictationOutcome.TranscribeFailed("IllegalArgumentException"), heard)
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

    private fun cfg(sttApiKey: String = "sk-whisper", agent: Boolean = true) = AiSettings.Config(
        provider = AiSettings.Provider.OpenAi,
        apiKey = "sk-chat",
        model = "gpt-4o",
        sttApiKey = sttApiKey,
        agentEnabled = agent,
    )

    @Test
    fun `what stops a dictation before it records`() {
        assertEquals(DictationRun.NO_SHIP, DictationRun.blocker(cfg(), hasShip = false))
        assertNull(DictationRun.blocker(cfg(), hasShip = true))
        assertEquals(DictationRun.NO_MODEL, DictationRun.blocker(cfg(agent = false), hasShip = true))
        val noSpeech = cfg(sttApiKey = "").copy(provider = AiSettings.Provider.Anthropic, apiKey = "sk-ant")
        assertEquals(DictationRun.NO_SPEECH_TO_TEXT, DictationRun.blocker(noSpeech, hasShip = true))
    }

    @Test
    fun `whisper's segments become one line`() {
        val text = DictationRun.text(listOf(CallTranscribe.Segment(0, " buy milk "), CallTranscribe.Segment(900, "and eggs")))
        assertEquals("buy milk and eggs", text)
        assertEquals("", DictationRun.text(emptyList()))
    }
}
