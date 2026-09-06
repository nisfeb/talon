package io.nisfeb.talon.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The 44.1k mic / 48k remote mismatch is the whole reason this exists,
 * so the ratios here are the real ones.
 */
class PcmResampleTest {

    private fun ramp(samples: Int): ByteArray {
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val v = i * 10
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun sampleAt(pcm: ByteArray, i: Int): Int {
        val lo = pcm[i * 2].toInt() and 0xFF
        val hi = pcm[i * 2 + 1].toInt()
        return (hi shl 8) or lo
    }

    @Test
    fun sameRateIsUntouched() {
        val src = ramp(100)
        assertTrue(PcmResample.to(src, 48_000, 48_000) === src)
    }

    @Test
    fun upsamplingLengthensByTheRateRatio() {
        val out = PcmResample.to(ramp(441), 44_100, 48_000)
        // 441 samples of 44.1k is 10ms; at 48k that is 480 samples.
        assertEquals(480, out.size / 2)
    }

    @Test
    fun downsamplingShortensByTheRateRatio() {
        val out = PcmResample.to(ramp(480), 48_000, 44_100)
        assertEquals(441, out.size / 2)
    }

    @Test
    fun aRampStaysMonotonic() {
        // Linear interpolation of a ramp must not introduce a wobble;
        // this catches sign errors in the 16-bit unpack.
        val out = PcmResample.to(ramp(441), 44_100, 48_000)
        var prev = sampleAt(out, 0)
        for (i in 1 until out.size / 2) {
            val v = sampleAt(out, i)
            assertTrue(v >= prev, "sample $i went backwards: $prev -> $v")
            prev = v
        }
    }

    @Test
    fun tooShortToInterpolateIsReturnedAsIs() {
        val one = ramp(1)
        assertTrue(PcmResample.to(one, 44_100, 48_000) === one)
        assertEquals(0, PcmResample.to(ByteArray(0), 44_100, 48_000).size)
    }
}
