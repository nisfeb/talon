package io.nisfeb.talon.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The WAV container iOS plays through.
 *
 * Desktop and Android take raw PCM, so a wrong header would only ever
 * show up on the one platform that can't be run here. Verified against
 * ffprobe once by hand; this keeps it that way.
 */
class CallSoundsWavTest {

    private fun le32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or
            ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or
            ((b[at + 3].toInt() and 0xFF) shl 24)

    private fun le16(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun ascii(b: ByteArray, at: Int, n: Int) =
        (0 until n).map { b[at + it].toInt().toChar() }.joinToString("")

    @Test
    fun theHeaderDescribesTheSamplesThatFollow() {
        val pcm = CallSounds.joined()
        val wav = CallSounds.wav(pcm)

        assertEquals(44 + pcm.size, wav.size, "44-byte header plus the samples")
        assertEquals("RIFF", ascii(wav, 0, 4))
        assertEquals("WAVE", ascii(wav, 8, 4))
        assertEquals("fmt ", ascii(wav, 12, 4))
        assertEquals("data", ascii(wav, 36, 4))

        // RIFF size counts everything after the first 8 bytes.
        assertEquals(36 + pcm.size, le32(wav, 4))
        assertEquals(16, le32(wav, 16), "PCM fmt chunk is 16 bytes")
        assertEquals(1, le16(wav, 20), "1 = uncompressed PCM")
        assertEquals(CallSounds.CHANNELS, le16(wav, 22))
        assertEquals(CallSounds.SAMPLE_RATE, le32(wav, 24))
        assertEquals(CallSounds.BITS_PER_SAMPLE, le16(wav, 34))
        assertEquals(pcm.size, le32(wav, 40), "data size must match the payload")

        // byteRate and blockAlign are derived; a decoder that trusts
        // them and a payload that disagrees is silence or noise.
        assertEquals(
            CallSounds.SAMPLE_RATE * CallSounds.CHANNELS * CallSounds.BITS_PER_SAMPLE / 8,
            le32(wav, 28),
        )
        assertEquals(CallSounds.CHANNELS * CallSounds.BITS_PER_SAMPLE / 8, le16(wav, 32))
        assertTrue(wav.copyOfRange(44, wav.size).contentEquals(pcm))
    }

    @Test
    fun theGapIsSilenceOfTheRightLength() {
        // iOS loops seamlessly, so the ring's cadence lives in the
        // buffer. A gap of the wrong length is a drone or a stutter.
        val pcm = CallSounds.ringback()
        val withGap = CallSounds.withGap(pcm, 3_000)
        val addedSamples = (withGap.size - pcm.size) / 2
        assertEquals(CallSounds.SAMPLE_RATE * 3, addedSamples)
        assertTrue(
            withGap.copyOfRange(pcm.size, withGap.size).all { it.toInt() == 0 },
            "the gap must be silence, not a repeat",
        )
    }

    @Test
    fun leavingIsArrivingBackwards() {
        val j = CallSounds.joined()
        val l = CallSounds.left()
        assertEquals(j.size, l.size)
        // Sample-wise, not byte-wise: reversing bytes would swap each
        // sample's halves and produce noise rather than a tone.
        val n = j.size / 2
        for (i in 0 until n) {
            assertEquals(j[(n - 1 - i) * 2], l[i * 2])
            assertEquals(j[(n - 1 - i) * 2 + 1], l[i * 2 + 1])
        }
    }
}
