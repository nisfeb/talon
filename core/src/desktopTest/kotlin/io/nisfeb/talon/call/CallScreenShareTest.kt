package io.nisfeb.talon.call

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A 1:1 call's shared screen reaches the other end, whoever placed the
 * call. The answering side shared on its first video transceiver, which
 * no m-line pointed at: its share showed in its own preview and nowhere
 * else, and so did a change of camera. The fake screen pushes frames
 * because a test machine has none to capture; the rest is real.
 */
class CallScreenShareTest {
    private val shared = ScreenSource(7, "Fake screen", isWindow = false)

    private fun call(block: suspend (caller: DesktopCallEngine, callee: DesktopCallEngine) -> Unit) = runBlocking {
        val caller = DesktopCallEngine(screenCapture = FakeScreen())
        val callee = DesktopCallEngine(screenCapture = FakeScreen())
        try {
            caller.setAnswer(callee.acceptOffer(caller.createOffer()))
            val live = withTimeoutOrNull(20_000) {
                while (caller.state.value != MediaState.Live || callee.state.value != MediaState.Live) delay(100)
                true
            }
            assertTrue(live == true, "the call never connected: ${caller.state.value} / ${callee.state.value}")
            block(caller, callee)
        } finally {
            caller.close()
            callee.close()
        }
    }

    /** True once [engine] sees the far side's picture: frames, which is what the call view shows. */
    private suspend fun seesPicture(engine: DesktopCallEngine) = withTimeoutOrNull(10_000) {
        while (!engine.video.value.remoteOn) delay(50)
        true
    } == true

    @Test
    fun theCalleesShareReachesTheCaller() = call { caller, callee ->
        assertTrue(callee.setScreenShare(shared), "the share would not start")
        assertTrue(seesPicture(caller), "the caller never saw the callee's screen")
    }

    @Test
    fun theCallersShareReachesTheCallee() = call { caller, callee ->
        assertTrue(caller.setScreenShare(shared), "the share would not start")
        assertTrue(seesPicture(callee), "the callee never saw the caller's screen")
    }

    // Only the caller restarts ICE, so the callee answers again mid-call
    // and takes up its video transceiver again. With the camera off it
    // must keep the screen on it, not put the stopped camera back.
    @Test
    fun anIceRestartKeepsTheCalleesShare() = call { caller, callee ->
        assertTrue(callee.setScreenShare(shared))
        assertTrue(seesPicture(caller))
        caller.setAnswer(callee.acceptOffer(caller.restartIce()!!))
        // Longer than the picture takes to read as gone.
        delay(4_500)
        assertTrue(callee.video.value.sharing, "the callee still says it shares")
        assertTrue(caller.video.value.remoteOn, "the screen stopped reaching the caller after the restart")
    }
}
