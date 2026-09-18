package io.nisfeb.talon.ui

import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.component.CallbackMediaPlayerComponent
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Proof that libvlc really hands this build decoded frames, which is
 * the half of the inline player no unit test can reason about. Needs a
 * file and a machine with VLC, so it is asked for by name:
 *
 *   TALON_VIDEO_SMOKE=/path/to/sample.mp4 ./gradlew :composeApp:desktopTest --tests '*DesktopVideoSmokeTest'
 */
class DesktopVideoSmokeTest {

    @Test
    fun `libvlc decodes frames into the buffer the row draws`() {
        val path = System.getenv("TALON_VIDEO_SMOKE")
        if (path.isNullOrBlank()) {
            println("DesktopVideoSmokeTest: no file given; skipped")
            return
        }
        assertTrue(NativeDiscovery().discover(), "no libvlc on this machine, so the row would fall back to the link")

        val frames = AtomicInteger()
        val first = CountDownLatch(1)
        var width = 0
        var height = 0
        val render = object : RenderCallback {
            override fun lock(mediaPlayer: MediaPlayer) = Unit
            override fun unlock(mediaPlayer: MediaPlayer) = Unit
            override fun display(mediaPlayer: MediaPlayer, buffers: Array<out ByteBuffer>, format: BufferFormat, w: Int, h: Int) {
                // The row reads the first buffer as little-endian ints;
                // a format that did not fill it would draw a black box.
                val filled = buffers.firstOrNull()?.capacity() ?: 0
                if (filled >= w * h * 4) {
                    width = w
                    height = h
                    frames.incrementAndGet()
                    first.countDown()
                }
            }
        }
        val formats = object : BufferFormatCallback {
            override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat = RV32BufferFormat(sourceWidth, sourceHeight)
            override fun newFormatSize(w: Int, h: Int, dw: Int, dh: Int) = Unit
            override fun allocatedBuffers(buffers: Array<out ByteBuffer>) = Unit
        }
        val component = CallbackMediaPlayerComponent(null, null, null, true, render, formats, null)
        try {
            component.mediaPlayer().media().play(path)
            assertTrue(first.await(20, TimeUnit.SECONDS), "no frame arrived in twenty seconds")
            Thread.sleep(500)
            component.mediaPlayer().controls().pause()
            println("DesktopVideoSmokeTest: ${frames.get()} frames at ${width}x$height")
            assertTrue(frames.get() > 1, "only one frame arrived; playback is not running")
            assertTrue(width > 0 && height > 0)
        } finally {
            runCatching { component.release() }
        }
    }
}
