package io.nisfeb.talon.ui

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The desktop renderer's colour conversion.
 *
 * Every other part of video is native plumbing that only a device can
 * exercise; this is arithmetic, and it is the part that fails without
 * saying so — wrong coefficients or a mishandled stride still produce
 * a picture, just the wrong one. So it gets the one test.
 */
class VideoFrameConversionTest {

    /** Planes for a solid [y]/[u]/[v], with padded strides so a stride
     *  bug cannot hide behind stride == width. */
    private fun planes(w: Int, h: Int, y: Int, u: Int, v: Int): Array<Any> {
        val strideY = w + 7
        val strideUV = (w / 2) + 3
        val yb = ByteBuffer.allocate(strideY * h).apply {
            for (i in 0 until capacity()) put(i, y.toByte())
        }
        val ub = ByteBuffer.allocate(strideUV * (h / 2)).apply {
            for (i in 0 until capacity()) put(i, u.toByte())
        }
        val vb = ByteBuffer.allocate(strideUV * (h / 2)).apply {
            for (i in 0 until capacity()) put(i, v.toByte())
        }
        return arrayOf(yb, ub, vb, strideY, strideUV)
    }

    private fun convert(w: Int, h: Int, y: Int, u: Int, v: Int): IntArray {
        val (yb, ub, vb, sy, suv) = planes(w, h, y, u, v)
        val out = IntArray(w * h)
        i420ToRgb(
            yb as ByteBuffer, ub as ByteBuffer, vb as ByteBuffer,
            sy as Int, suv as Int, suv, w, h, out,
        )
        return out
    }

    private fun near(actual: Int, expected: Int, slack: Int = 4) =
        abs(actual - expected) <= slack

    @Test
    fun `full luma with neutral chroma is white`() {
        val px = convert(8, 4, y = 235, u = 128, v = 128)
        px.forEach {
            assertTrue(near((it shr 16) and 0xff, 255), "r was ${(it shr 16) and 0xff}")
            assertTrue(near((it shr 8) and 0xff, 255), "g was ${(it shr 8) and 0xff}")
            assertTrue(near(it and 0xff, 255), "b was ${it and 0xff}")
        }
    }

    @Test
    fun `black level is black, not clipped noise`() {
        convert(8, 4, y = 16, u = 128, v = 128).forEach {
            assertTrue(it == 0, "expected black, got ${it.toString(16)}")
        }
    }

    @Test
    fun `chroma actually moves the colour`() {
        // High V is the red-difference axis; red should dominate.
        val red = convert(8, 4, y = 128, u = 128, v = 240).first()
        assertTrue(
            (red shr 16 and 0xff) > (red shr 8 and 0xff) + 60,
            "expected a red cast, got ${red.toString(16)}",
        )
        // High U is the blue-difference axis.
        val blue = convert(8, 4, y = 128, u = 240, v = 128).first()
        assertTrue(
            (blue and 0xff) > (blue shr 8 and 0xff) + 60,
            "expected a blue cast, got ${blue.toString(16)}",
        )
    }

    @Test
    fun `every pixel is written, including the last row`() {
        val px = convert(16, 8, y = 235, u = 128, v = 128)
        assertTrue(px.none { it == 0 }, "some pixels were never written")
    }

    @Test
    fun `row stride is honoured, not assumed equal to width`() {
        // A solid image cannot catch a stride bug: reading at the wrong
        // offset still lands on the same constant, which is exactly how
        // the first version of this test passed against a deliberately
        // broken conversion. So give every row its own luma and fill
        // the padding with a value no row uses.
        val w = 16
        val h = 8
        val strideY = w + 7
        val strideUV = (w / 2) + 3
        val y = ByteBuffer.allocate(strideY * h)
        for (i in 0 until y.capacity()) y.put(i, 0xFF.toByte()) // padding sentinel
        for (row in 0 until h) {
            val luma = 16 + row * 20 // distinct, and inside the legal range
            for (col in 0 until w) y.put(row * strideY + col, luma.toByte())
        }
        val neutral = { n: Int ->
            ByteBuffer.allocate(n).apply { for (i in 0 until n) put(i, 128.toByte()) }
        }
        val out = IntArray(w * h)
        i420ToRgb(
            y, neutral(strideUV * (h / 2)), neutral(strideUV * (h / 2)),
            strideY, strideUV, strideUV, w, h, out,
        )

        // Neutral chroma means grey, so every channel equals the luma
        // ramp. Each row must be uniform and strictly brighter than the
        // one above it.
        var previous = -1
        for (row in 0 until h) {
            val first = out[row * w] and 0xff
            for (col in 0 until w) {
                assertTrue(
                    (out[row * w + col] and 0xff) == first,
                    "row $row is not uniform — a stride bug reads across rows",
                )
            }
            assertTrue(
                first > previous,
                "row $row ($first) is not brighter than row ${row - 1} ($previous)",
            )
            previous = first
        }
    }
}
