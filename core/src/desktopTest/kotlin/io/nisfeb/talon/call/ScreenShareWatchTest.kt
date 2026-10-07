package io.nisfeb.talon.call

import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCRtpTransceiverDirection
import dev.onvoid.webrtc.RTCRtpTransceiverInit
import dev.onvoid.webrtc.media.video.CustomVideoSource
import dev.onvoid.webrtc.media.video.NativeI420Buffer
import dev.onvoid.webrtc.media.video.VideoFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A share that sends nothing ends itself. On Wayland a cancelled system
 * dialog, or one never answered, left "Stop sharing" over a blank
 * picture for the rest of the call, and so did a window that closed.
 */
class ScreenShareWatchTest {
    /** A capture that pushes frames for [forMs] (none at 0), then goes quiet. */
    private fun capture(forMs: Long) = ScreenCapture {
        val src = CustomVideoSource()
        val on = AtomicBoolean(true)
        val until = System.currentTimeMillis() + forMs
        val pusher = thread(isDaemon = true) {
            while (on.get() && System.currentTimeMillis() < until) {
                val frame = VideoFrame(NativeI420Buffer.allocate(64, 48), System.nanoTime())
                runCatching { src.pushFrame(frame) }
                frame.release()
                Thread.sleep(30)
            }
        }
        OpenedCapture(src) {
            on.set(false)
            pusher.join(1_000)
            runCatching { src.dispose() }
        }
    }

    private fun endsItself(forMs: Long) = runBlocking {
        val factory = DesktopWebRtcFactory.get()
        val pc = factory.createPeerConnection(RTCConfiguration(), PeerConnectionObserver { _: RTCIceCandidate -> })
        val camera = factory.createVideoTrack("cam", CustomVideoSource())
        val sender = pc.addTransceiver(camera, RTCRtpTransceiverInit().apply { direction = RTCRtpTransceiverDirection.SEND_ONLY }).sender
        val ended = CompletableDeferred<Unit>()
        val slot = ScreenShareSlot(factory, capture(forMs), onEnded = { ended.complete(Unit) }, firstFrameMs = 400, frameGapMs = 400)
        try {
            assertTrue(slot.set(sender, ScreenSource(1, "a screen", isWindow = false), camera) {})
            assertTrue(slot.active)
            withTimeout(10_000) { ended.await() }
            assertFalse(slot.active, "ended, and the camera's track is back on the sender")
        } finally {
            slot.release()
            pc.close()
        }
    }

    @Test
    fun aShareThatNeverSendsAFrameEndsItself() = endsItself(forMs = 0)

    @Test
    fun aShareThatStopsSendingEndsItself() = endsItself(forMs = 600)
}
