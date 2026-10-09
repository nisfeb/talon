package io.nisfeb.talon.call

import dev.onvoid.webrtc.media.video.CustomVideoSource
import dev.onvoid.webrtc.media.video.I420Buffer
import dev.onvoid.webrtc.media.video.NativeI420Buffer
import dev.onvoid.webrtc.media.video.VideoFrame
import dev.onvoid.webrtc.media.video.VideoFrameBuffer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every video sink releases each frame it is given: webrtc-java hands a
 * sink its own native copy of every frame and frees it only on release
 * (devopvoid/webrtc-java#191). Two sinks that only noted the time leaked
 * 16 GB in an afternoon of calls and screen sharing (2026-10-09).
 */
class ReleasingSinkTest {
    /** A frame buffer that counts its releases. */
    private class Counted : VideoFrameBuffer {
        var released = 0
        override fun retain() = Unit
        override fun release() { released++ }
        override fun getWidth() = 2
        override fun getHeight() = 2
        override fun toI420(): I420Buffer = error("not read")
        override fun cropAndScale(cropX: Int, cropY: Int, cropWidth: Int, cropHeight: Int, scaleWidth: Int, scaleHeight: Int): VideoFrameBuffer = error("not read")
    }

    @Test
    fun `a frame is released once, after the sink's work, even when that throws`() {
        val buffer = Counted()
        var seen = -1
        releasingSink { seen = buffer.released }.onVideoFrame(VideoFrame(buffer, 0, 0L))
        assertEquals(0, seen, "released after the work, not before")
        assertEquals(1, buffer.released)
        runCatching { releasingSink { error("could not draw") }.onVideoFrame(VideoFrame(buffer, 0, 0L)) }
        assertEquals(2, buffer.released, "a sink that throws still lets the frame go")
    }

    @Test
    fun `the screen share's frame counter lets each frame go`() {
        val slot = ScreenShareSlot(DesktopWebRtcFactory.get(), ScreenCapture { error("not opened") }, onEnded = {})
        val buffer = Counted()
        repeat(3) { slot.frameSeen.onVideoFrame(VideoFrame(buffer, 0, 0L)) }
        assertEquals(3, buffer.released)
        slot.release()
    }

    private fun rssMb(): Long = File("/proc/self/status").readLines().first { it.startsWith("VmRSS:") }
        .split(Regex("\\s+"))[1].toLong() / 1024

    /**
     * Real frames through a real track into a sink, as a call delivers
     * them. A sink that keeps its copies holds 1280x720x1.5 bytes each,
     * about 415 MB for these 300.
     */
    @Test
    fun `real frames through a track leave no native copies behind`() {
        if (!File("/proc/self/status").exists()) return // Linux measures resident memory this way
        val factory = DesktopWebRtcFactory.get()
        val source = CustomVideoSource()
        val track = factory.createVideoTrack("frames", source)
        var count = 0
        val sink = releasingSink { count++ }
        track.addSink(sink)
        try {
            fun push(n: Int) = repeat(n) {
                val buffer = NativeI420Buffer.allocate(1280, 720)
                val frame = VideoFrame(buffer, 0, System.nanoTime())
                source.pushFrame(frame)
                frame.release()
            }
            push(30) // the allocator's own first growth, before measuring
            val before = rssMb()
            push(300)
            val grew = rssMb() - before
            assertTrue(count >= 300, "the sink saw the frames: $count")
            assertTrue(grew < 120, "resident memory grew $grew MB over 300 frames")
        } finally {
            runCatching { track.removeSink(sink) }
            runCatching { track.dispose() }
            runCatching { source.dispose() }
        }
    }
}
