package io.nisfeb.talon.call

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test

/**
 * Pure media-layer bisect: two engines in one process, SDPs handed
 * over directly (no Urbit). If this sticks at CHECKING, the problem
 * is webrtc-java/host networking; if it connects, the problem is in
 * the signaling path. Opt-in like the E2E (spawns real sockets/audio).
 */
class EngineLoopbackTest {
    @Test
    fun loopback() {
        if (System.getenv("TRUNK_E2E") == null) {
            println("TRUNK_E2E not set — skipping engine loopback test")
            return
        }
        runBlocking {
            val a = DesktopCallEngine()
            val b = DesktopCallEngine()
            val offer = a.createOffer()
            val answer = b.acceptOffer(offer)
            a.setAnswer(answer)
            withTimeout(30_000) { a.state.first { it == MediaState.Live } }
            withTimeout(30_000) { b.state.first { it == MediaState.Live } }
            println("loopback: both live")
            a.close(); b.close()
        }
    }
}
