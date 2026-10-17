package io.nisfeb.talon.ai

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where a recorded call is heard, and how each speaker's track is cut
 * into stretches of speech: Armillary's speech route answers words with
 * no timestamps, so a stretch's start is where its words go in the call.
 */
class CallRecordingPublisherTest {
    private val whisper = "openai/whisper-large-v3-turbo"
    private fun cfg(transcription: Boolean = true, speech: Boolean = true) = paidSettings(
        row = armillaryRow(suggested = mapOf("default" to "a/1") + if (speech) mapOf(TRANSCRIPTION to whisper) else emptyMap()),
    ).let { c -> c.copy(savedProfile = c.savedProfile!!.copy(features = c.savedProfile!!.features + (AiFeature.Transcription to FeatureSetting(on = transcription)))) }

    @Test
    fun `a call is heard by Armillary's transcription model, while transcription is on`() {
        assertEquals(Hearing("https://openrouter.ai/api/v1", "sk-or-lease", whisper), CallRecordingPublisher.hearingFrom(cfg()))
        assertNull(CallRecordingPublisher.hearingFrom(cfg(transcription = false)), "switched off")
        assertNull(CallRecordingPublisher.hearingFrom(cfg(speech = false)), "the vendor offers no speech model")
        assertEquals(
            Hearing("https://openrouter.ai/api/v1", "sk-or-lease", whisper),
            CallRecordingPublisher.hearingFrom(cfg().copy(sttApiKey = "sk-whisper")),
            "an own Whisper key left from before is not used",
        )
    }

    private val rate = 16_000

    /** [ms] of a 220 Hz tone at [amp], or of near-silence (a little hiss) where [amp] is 0. */
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

    private fun ms(byte: Int) = byte.toLong() * 1000 / (rate * 2)

    @Test
    fun `a track is cut where the speaker pauses, and a short pause does not cut it`() {
        val pcm = sound(1_000, 0) + sound(2_000, 4_000) + sound(1_500, 0) + sound(1_000, 4_000) + sound(300, 0) + sound(1_000, 4_000) + sound(1_000, 0)
        val spans = CallRecordingPublisher.speechSpans(pcm, rate)
        assertEquals(2, spans.size, spans.toString())
        // Each starts a little before its speech, padded by 200 ms.
        assertTrue(ms(spans[0].first) in 780L..820L, "first at ${ms(spans[0].first)}")
        assertTrue(ms(spans[1].first) in 4_280L..4_320L, "second at ${ms(spans[1].first)}")
        // The 300 ms pause is inside the second stretch.
        assertTrue(ms(spans[1].last) >= 6_500, "second ends at ${ms(spans[1].last)}")
        spans.forEach { assertTrue(it.first % 2 == 0 && (it.last + 1) % 2 == 0, "whole samples: $it") }
    }

    @Test
    fun `a track that is digital silence between words, or a noisy room, is still heard`() {
        val zeros = ByteArray(rate * 2) // a second of nothing at all
        assertEquals(1, CallRecordingPublisher.speechSpans(zeros + sound(1_000, 4_000) + zeros, rate).size)
        // A room as loud as 800 with a voice at 4,000 over it.
        val noisy = sound(2_000, 1_130) + sound(1_000, 5_650) + sound(2_000, 1_130)
        assertEquals(1, CallRecordingPublisher.speechSpans(noisy, rate).size)
    }

    @Test
    fun `a voice must be three times the room, a short word counts, and padding stops at the track's ends`() {
        // A room at about 400 and a voice at about 1,100: under three times the room, so not speech.
        assertTrue(CallRecordingPublisher.speechSpans(sound(2_000, 566) + sound(1_000, 1_556) + sound(2_000, 566), rate).isEmpty())
        // A 280 ms word is a word.
        assertEquals(1, CallRecordingPublisher.speechSpans(sound(1_000, 0) + sound(280, 4_000) + sound(1_000, 0), rate).size)
        // The end is padded by 200 ms too.
        val one = CallRecordingPublisher.speechSpans(sound(1_000, 0) + sound(2_000, 4_000) + sound(1_000, 0), rate).single()
        assertTrue(ms(one.last + 1) in 3_180L..3_240L, "ends at ${ms(one.last + 1)}")
        // Speech to the very start and end: padding stops at the track.
        val edge = sound(1_500, 4_000) + sound(1_000, 0) + sound(1_500, 4_000)
        val spans = CallRecordingPublisher.speechSpans(edge, rate)
        assertEquals(0, spans.first().first)
        assertTrue(spans.last().last < edge.size, "never past the track")
    }

    @Test
    fun `a long stretch is cut to fit, and silence or a click is nothing`() {
        val long = CallRecordingPublisher.speechSpans(sound(60_000, 4_000), rate)
        assertEquals(3, long.size)
        assertTrue(long.all { ms(it.last - it.first + 1) <= 25_000 })
        assertEquals(0, long.first().first)
        assertEquals(long[0].last + 1, long[1].first, "back to back, nothing lost")
        assertTrue(CallRecordingPublisher.speechSpans(sound(10_000, 0), rate).isEmpty())
        assertTrue(CallRecordingPublisher.speechSpans(ByteArray(rate * 2 * 5), rate).isEmpty(), "digital silence")
        assertTrue(CallRecordingPublisher.speechSpans(sound(1_000, 0) + sound(120, 4_000) + sound(1_000, 0), rate).isEmpty(), "a click")
        assertTrue(CallRecordingPublisher.speechSpans(ByteArray(0), rate).isEmpty())
    }
}
