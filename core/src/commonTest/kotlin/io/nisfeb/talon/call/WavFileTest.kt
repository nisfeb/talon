package io.nisfeb.talon.call

import kotlin.test.Test
import kotlin.test.assertEquals

class WavFileTest {

    private fun le32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or
            ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or
            ((b[at + 3].toInt() and 0xFF) shl 24)

    private fun le16(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    @Test
    fun writesHeaderWithGivenRate() {
        val pcm = ByteArray(100) { it.toByte() }
        val wav = WavFile.encode(pcm, sampleRate = 48_000)
        assertEquals("RIFF", wav.decodeToString(0, 4))
        assertEquals("WAVE", wav.decodeToString(8, 12))
        assertEquals(48_000, le32(wav, 24)) // sample rate
        assertEquals(1, le16(wav, 22)) // mono
        assertEquals(16, le16(wav, 34)) // bits per sample
        assertEquals(48_000 * 2, le32(wav, 28)) // byte rate = rate*ch*2
        assertEquals(pcm.size, le32(wav, 40)) // data size
        assertEquals(44 + pcm.size, wav.size)
    }
}
