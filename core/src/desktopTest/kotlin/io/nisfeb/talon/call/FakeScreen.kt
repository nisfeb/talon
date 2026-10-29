package io.nisfeb.talon.call

import dev.onvoid.webrtc.media.video.CustomVideoSource
import dev.onvoid.webrtc.media.video.NativeI420Buffer
import dev.onvoid.webrtc.media.video.VideoFrame
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** A screen that pushes 320x240 frames at about 30 a second. */
internal class FakeScreen : ScreenCapture {
    val opened = mutableListOf<ScreenSource>()
    val closed = AtomicInteger()
    override fun open(source: ScreenSource): OpenedCapture {
        opened += source
        val src = CustomVideoSource()
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val pusher = thread(isDaemon = true, name = "fake-screen") {
            while (running.get()) {
                val frame = VideoFrame(NativeI420Buffer.allocate(320, 240), System.nanoTime())
                runCatching { src.pushFrame(frame) }
                frame.release()
                Thread.sleep(33)
            }
        }
        return OpenedCapture(src) {
            running.set(false)
            pusher.join(1_000)
            closed.incrementAndGet()
            runCatching { src.dispose() }
        }
    }
}
