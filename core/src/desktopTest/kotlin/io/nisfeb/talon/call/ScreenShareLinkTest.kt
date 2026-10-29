package io.nisfeb.talon.call

import dev.onvoid.webrtc.media.video.VideoTrackSink
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A shared screen crosses a real peer connection: an up link shares a
 * fake screen, and the down link wired to it, as Galène wires them,
 * receives its frames. The fake pushes frames because a test machine
 * has no screen to capture; everything after the source is real.
 */
class ScreenShareLinkTest {
    @Test
    fun aSharedScreenArrivesAtTheOtherEnd() = runBlocking {
        val screen = FakeScreen()
        val up = DesktopPeerLink(emptyList(), sendAudio = true, screenCapture = screen)
        val down = DesktopPeerLink(emptyList(), sendAudio = false)
        val frames = AtomicInteger()
        val sink = VideoTrackSink { frames.incrementAndGet() }
        try {
            up.onLocalCandidate { down.addRemoteCandidate(it) }
            down.onLocalCandidate { up.addRemoteCandidate(it) }
            up.applyAnswer(down.answerTo(up.offer()))
            val live = withTimeoutOrNull(20_000) {
                while (up.state.value != MediaState.Live || down.state.value != MediaState.Live) delay(100)
                true
            }
            assertTrue(live == true, "the links never connected: ${up.state.value} / ${down.state.value}")

            val shared = ScreenSource(42, "Fake screen", isWindow = false)
            assertTrue(up.setScreenShare(shared), "the share would not start")
            assertEquals(listOf(shared), screen.opened)
            assertTrue(up.video.value.localOn && up.video.value.sharing)
            assertTrue(up.localVideoTrack != null, "the self tile has the shared track")

            val track = withTimeoutOrNull(10_000) {
                while (down.remoteVideoTrack == null) delay(50)
                down.remoteVideoTrack
            }
            assertTrue(track != null, "the down link never got a video track")
            track.addSink(sink)
            val arrived = withTimeoutOrNull(10_000) {
                while (frames.get() < 10) delay(50)
                true
            }
            assertTrue(arrived == true, "only ${frames.get()} shared frames arrived")

            assertTrue(up.setScreenShare(null))
            assertEquals(1, screen.closed.get(), "stopping ended the capture")
            assertFalse(up.video.value.sharing)
            assertFalse(up.video.value.localOn)
        } finally {
            runCatching { down.remoteVideoTrack?.removeSink(sink) }
            up.close()
            down.close()
        }
    }

    @Test
    fun aShareOnAClosedLinkStartsNothing() = runBlocking {
        val screen = FakeScreen()
        val up = DesktopPeerLink(emptyList(), sendAudio = true, screenCapture = screen)
        up.close()
        assertFalse(up.setScreenShare(ScreenSource(1, "x", isWindow = false)))
        assertTrue(screen.opened.isEmpty(), "a capture opened after the hang-up has nothing to stop it")
    }
}
