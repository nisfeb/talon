package io.nisfeb.talon.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PcmMixTest {

    private fun pcm(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun samples(b: ByteArray): List<Int> =
        (0 until b.size / 2).map {
            val lo = b[it * 2].toInt() and 0xFF
            val hi = b[it * 2 + 1].toInt()
            (hi shl 8) or lo
        }

    @Test
    fun sumsSamples() {
        val mix = PcmMix.mix(listOf(pcm(10000, -5000), pcm(20000, -5000)))
        assertEquals(listOf(30000, -10000), samples(mix))
    }

    @Test
    fun clipsAtBounds() {
        val mix = PcmMix.mix(listOf(pcm(30000, -30000), pcm(10000, -10000)))
        assertEquals(listOf(32767, -32768), samples(mix))
    }

    @Test
    fun padsShorterTrackWithSilence() {
        val mix = PcmMix.mix(listOf(pcm(100, 200, 300), pcm(50)))
        assertEquals(listOf(150, 200, 300), samples(mix))
    }

    @Test
    fun singleTrackPassesThrough() {
        val one = pcm(1, 2, 3)
        assertTrue(PcmMix.mix(listOf(one)).contentEquals(one))
        assertEquals(0, PcmMix.mix(emptyList()).size)
    }
}
