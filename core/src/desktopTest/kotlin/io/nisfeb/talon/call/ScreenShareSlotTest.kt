package io.nisfeb.talon.call

import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCRtpTransceiverDirection
import dev.onvoid.webrtc.RTCRtpTransceiverInit
import dev.onvoid.webrtc.media.video.CustomVideoSource
import dev.onvoid.webrtc.media.video.VideoTrack
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The share's life on a sender: a share that will not start leaves the
 * camera alone, each share's track is freed, and a capture outlives the
 * link that opened it while another link still sends it.
 */
class ScreenShareSlotTest {
    private val factory = DesktopWebRtcFactory.get()
    private val screen = ScreenSource(1, "a screen", isWindow = false)

    /** A capture that counts its opens and closes and sends nothing. */
    private class Counting : ScreenCapture {
        val opened = AtomicInteger()
        val closed = AtomicInteger()
        override fun open(source: ScreenSource): OpenedCapture {
            opened.incrementAndGet()
            val src = CustomVideoSource()
            return OpenedCapture(src) { closed.incrementAndGet(); runCatching { src.dispose() } }
        }
    }

    private fun onSender(body: (dev.onvoid.webrtc.RTCRtpSender, VideoTrack) -> Unit) {
        val pc = factory.createPeerConnection(RTCConfiguration(), PeerConnectionObserver { _: RTCIceCandidate -> })
        val camera = factory.createVideoTrack("cam", CustomVideoSource())
        val sender = pc.addTransceiver(camera, RTCRtpTransceiverInit().apply { direction = RTCRtpTransceiverDirection.SEND_ONLY }).sender
        try { body(sender, camera) } finally { pc.close() }
    }

    /** Whether libwebrtc still holds [track]: dispose() clears the handle. */
    private fun live(track: VideoTrack): Boolean {
        val f = dev.onvoid.webrtc.internal.NativeObject::class.java.getDeclaredField("nativeHandle")
        f.isAccessible = true
        return f.getLong(track) != 0L
    }

    @Test
    fun aShareThatWillNotStartLeavesTheCameraOn() = onSender { sender, camera ->
        camera.isEnabled = true
        var stopped = false
        val slot = ScreenShareSlot(factory, ScreenCapture { error("the dialog was cancelled") }, onEnded = {})
        try {
            assertFalse(slot.set(sender, screen, camera) { stopped = true })
            assertTrue(camera.isEnabled, "the camera still sends")
            assertFalse(stopped, "and its device was not stopped")
            assertEquals(camera.id, sender.track?.id, "it is still on the sender")
        } finally {
            slot.release()
        }
    }

    @Test
    fun eachSharesTrackIsFreed() = onSender { sender, camera ->
        val slot = ScreenShareSlot(factory, Counting(), onEnded = {})
        try {
            assertTrue(slot.set(sender, screen, camera) {})
            val first = slot.track!!
            assertTrue(live(first), "held while it is shared")
            assertTrue(slot.set(sender, ScreenSource(2, "another", isWindow = false), camera) {})
            assertFalse(live(first), "the share it replaced")
            val second = slot.track!!
            assertTrue(slot.set(sender, null, camera) {})
            assertFalse(live(second), "the share stopped")
            assertEquals(camera.id, sender.track?.id)
        } finally {
            slot.release()
        }
    }

    @Test
    fun aCaptureOutlivesTheLinkThatOpenedItWhileAnotherSendsIt() = runBlocking {
        val screens = Counting()
        val shared = SharedScreenCapture(screens)
        val old = DesktopPeerLink(emptyList(), sendAudio = true, screenCapture = shared)
        val new = DesktopPeerLink(emptyList(), sendAudio = true, screenCapture = shared)
        try {
            assertTrue(old.setScreenShare(screen))
            assertTrue(new.setScreenShare(screen), "the republished link takes the share")
            old.close()
            assertEquals(1, screens.opened.get(), "opened once: no second system dialog")
            assertEquals(0, screens.closed.get(), "still sent by the new link")
            assertTrue(new.video.value.sharing)

            assertTrue(new.setScreenShare(null))
            assertEquals(1, screens.closed.get(), "ended with the last link sending it")
            assertTrue(new.setScreenShare(screen))
            assertEquals(2, screens.opened.get(), "a new share opens anew")
        } finally {
            old.close()
            new.close()
        }
    }
}
