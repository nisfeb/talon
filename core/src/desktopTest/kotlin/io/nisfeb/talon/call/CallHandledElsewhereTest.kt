package io.nisfeb.talon.call

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Another of our devices answered or declined: stop ringing here —
 * but only for the call this device is actually ringing for. A stale
 * handled notice must not silence a genuine newer ring.
 */
class CallHandledElsewhereTest {

    private fun controller(h: TrunkHarness) = CallController(
        h.session,
        CallEngineProvider { error("no media needed to ring") },
    )

    @Test
    fun handledForTheRingingCallStopsTheRing() = runBlocking<Unit> {
        val h = TrunkHarness()
        val c = controller(h)
        try {
            c.start()
            h.awaitConnected()
            h.emitFact("""{"recv":{"from":"~zod","sig":{"ring":{"id":"c1"}}}}""")
            withTimeout(10_000) { c.state.first { it is CallUiState.Incoming } }

            h.emitFact("""{"handled":"c1"}""")
            withTimeout(10_000) { c.state.first { it is CallUiState.None } }
        } finally {
            c.stop()
        }
    }

    @Test
    fun handledForSomeOtherCallLeavesTheRingAlone() = runBlocking {
        val h = TrunkHarness()
        val c = controller(h)
        try {
            c.start()
            h.awaitConnected()
            h.emitFact("""{"recv":{"from":"~zod","sig":{"ring":{"id":"c1"}}}}""")
            withTimeout(10_000) { c.state.first { it is CallUiState.Incoming } }

            h.emitFact("""{"handled":"c0"}""")
            // Facts are processed in order, so once this sentinel has
            // landed the stale notice above has been fully handled.
            h.emitFact("""{"policy":{"mode":"allow"}}""")
            withTimeout(10_000) {
                c.policy.first { it?.mode == CallPolicy.Mode.Allow }
            }

            assertTrue(
                c.state.value is CallUiState.Incoming,
                "a stale handled notice silenced a newer ring: ${c.state.value}",
            )
        } finally {
            c.stop()
        }
    }
}
