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

    // Signalling here does not trickle, so a restart offer is all the
    // candidates the far side will get. It waited on the first gather's
    // deferred, long complete, and went out with few or none.
    @Test
    fun aRestartOfferCarriesItsNewCandidates() {
        if (System.getenv("TRUNK_E2E") == null) {
            println("TRUNK_E2E not set — skipping engine loopback test")
            return
        }
        runBlocking {
            val a = DesktopCallEngine()
            val b = DesktopCallEngine()
            a.setAnswer(b.acceptOffer(a.createOffer()))
            withTimeout(30_000) { a.state.first { it == MediaState.Live } }
            val restart = a.restartIce()!!
            val candidates = restart.sdp.lines().count { it.startsWith("a=candidate") }
            kotlin.test.assertTrue(candidates > 0, "a restart offer with no candidates:\n${restart.sdp}")
            a.close(); b.close()
        }
    }
}
