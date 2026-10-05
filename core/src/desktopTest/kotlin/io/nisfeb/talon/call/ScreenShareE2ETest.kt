package io.nisfeb.talon.call

import dev.onvoid.webrtc.media.video.VideoTrackSink
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The real share on this desktop, end to end: the sources it lists, the
 * capture (on Wayland the system's dialog, which someone must answer),
 * and the frames arriving at a down link. Opt-in (TALON_SHARE_E2E=1):
 * it needs a display and a person.
 */
class ScreenShareE2ETest {
    @Test
    fun aRealShareArrivesAtTheOtherEnd() = runBlocking {
        if (System.getenv("TALON_SHARE_E2E") == null) {
            println("TALON_SHARE_E2E not set: skipping the real screen share")
            return@runBlocking
        }
        val up = DesktopPeerLink(emptyList(), sendAudio = true)
        val down = DesktopPeerLink(emptyList(), sendAudio = false)
        val frames = AtomicInteger()
        val sink = VideoTrackSink { frames.incrementAndGet() }
        try {
            up.onLocalCandidate { down.addRemoteCandidate(it) }
            down.onLocalCandidate { up.addRemoteCandidate(it) }
            up.applyAnswer(down.answerTo(up.offer()))
            withTimeoutOrNull(20_000) { while (up.state.value != MediaState.Live || down.state.value != MediaState.Live) delay(100) }
            val sources = up.screenSources()
            println("share e2e: sources offered: $sources")
            assertTrue(sources.isNotEmpty(), "nothing to share")
            assertTrue(up.setScreenShare(sources.first()), "the share would not start")
            println("share e2e: started; answer the system's dialog if one opens")
            val track = withTimeoutOrNull(20_000) { while (down.remoteVideoTrack == null) delay(50); down.remoteVideoTrack }
            assertTrue(track != null, "no video track at the other end")
            track.addSink(sink)
            val arrived = withTimeoutOrNull(55_000) { while (frames.get() < 30) delay(100); true }
            println("share e2e: ${frames.get()} frames arrived; sharing=${up.video.value.sharing}")
            assertTrue(arrived == true, "only ${frames.get()} shared frames arrived")
        } finally {
            runCatching { down.remoteVideoTrack?.removeSink(sink) }
            up.close()
            down.close()
        }
    }
}
