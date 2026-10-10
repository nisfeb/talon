package io.nisfeb.talon.ui

import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.onvoid.webrtc.media.video.NativeI420Buffer
import dev.onvoid.webrtc.media.video.VideoFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The desktop renderer's frames: no Java garbage per frame, each frame's
 * native image freed as the next replaces it, and still drawn right.
 * Each frame used to cost 8 MB of Java garbage and an 8 MB native bitmap
 * left for a collection, and Talon on Windows swung between 2 and 5 GB
 * through a screen share (2026-10-10).
 */
class VideoFrameChurnTest {
    // webrtc-java loads its native library as its factory class initialises.
    init { Class.forName("dev.onvoid.webrtc.PeerConnectionFactory") }

    /** A solid frame in BT.601 limited range. */
    private fun frame(w: Int, h: Int, y: Int, u: Int, v: Int): VideoFrame {
        val buf = NativeI420Buffer.allocate(w, h)
        fun fill(b: java.nio.ByteBuffer, x: Int) { for (i in 0 until b.capacity()) b.put(i, x.toByte()) }
        fill(buf.dataY, y); fill(buf.dataU, u); fill(buf.dataV, v)
        return VideoFrame(buf, 0)
    }

    @Test
    fun `a 1080p frame makes no Java garbage`() {
        val mx = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val converter = FrameConverter()
        val holder = FrameHolder()
        val f = frame(1920, 1080, 128, 128, 128)
        try {
            repeat(5) { holder.put(converter.toImage(f)) }
            val tid = Thread.currentThread().threadId()
            val before = mx.getThreadAllocatedBytes(tid)
            repeat(30) { holder.put(converter.toImage(f)) }
            val perFrame = (mx.getThreadAllocatedBytes(tid) - before) / 30
            assertTrue(perFrame < 64 * 1024, "each frame left $perFrame bytes of Java garbage (was 8 MB)")
        } finally {
            f.release()
            holder.close()
        }
    }

    @Test
    fun `each frame's image is closed when the next replaces it, and the last when the tile goes`() {
        val converter = FrameConverter()
        val holder = FrameHolder()
        val f = frame(64, 36, 128, 128, 128)
        try {
            val first = converter.toImage(f)
            val second = converter.toImage(f)
            holder.put(first)
            holder.put(second)
            assertTrue(first.isClosed, "the replaced frame is freed at once")
            assertTrue(!second.isClosed)
            assertEquals(64, holder.use { it.width })
            holder.close()
            assertTrue(second.isClosed, "the tile going frees its frame")
            val late = converter.toImage(f)
            holder.put(late)
            assertTrue(late.isClosed, "a frame arriving after the tile went is freed too")
            assertEquals(null, holder.use { it.width })
        } finally {
            f.release()
        }
    }

    @Test
    fun `a frame still draws in its own colours, fitted to the tile`() {
        // BT.601 red: Y 81, U 90, V 240.
        val f = frame(32, 16, 81, 90, 240)
        val holder = FrameHolder()
        try {
            holder.put(FrameConverter().toImage(f))
            val target = ImageBitmap(64, 64)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(target), Size(64f, 64f)) {
                holder.use { drawFitted(it, mirror = false, rotation = 0) }
            }
            val px = target.toPixelMap()
            val mid = px[32, 32]
            assertTrue(mid.red > 0.8f && mid.green < 0.2f && mid.blue < 0.2f, "the middle is red: $mid")
            assertEquals(1f, mid.alpha, "and opaque")
            // 32x16 fitted into 64x64 is 64x32, centred: the top band stays empty.
            assertEquals(0f, px[32, 4].alpha, "letterboxed above: ${px[32, 4]}")
        } finally {
            f.release()
            holder.close()
        }
    }

    // A tile showed nothing until a second frame came: the first wrote its
    // redraw counter 0 over 0. A sparse sender stayed black that long.
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test
    fun `a tile draws its first frame without waiting for a second`() = androidx.compose.ui.test.runComposeUiTest {
        val factory = dev.onvoid.webrtc.PeerConnectionFactory()
        val source = dev.onvoid.webrtc.media.video.CustomVideoSource()
        val track = factory.createVideoTrack("t", source)
        try {
            setContent {
                VideoTrackCanvas(track, on = true, mirror = false, modifier = androidx.compose.ui.Modifier.size(64.dp).testTag("tile"), onFrameAspect = null)
            }
            waitForIdle()
            val red = frame(64, 64, 81, 90, 240)
            source.pushFrame(red)
            red.release()
            waitUntil(timeoutMillis = 5_000) {
                val px = onNodeWithTag("tile").captureToImage().toPixelMap()[32, 32]
                px.red > 0.8f && px.green < 0.2f
            }
        } finally {
            track.dispose(); source.dispose(); factory.dispose()
        }
    }
}
