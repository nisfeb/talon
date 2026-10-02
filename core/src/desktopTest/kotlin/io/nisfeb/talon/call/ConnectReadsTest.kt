package io.nisfeb.talon.call

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The controller's six reads on connecting (ice, policy, version, sfu,
 * rooms, lines) ran one after another, and the subscription calls arrive
 * on waited for all six. They go at once.
 */
class ConnectReadsTest {

    @Test
    fun `the reads on connecting do not hold the subscription six round trips back`() = runBlocking<Unit> {
        val h = TrunkHarness()
        h.scryDelayMs = 400
        val c = CallController(h.session, CallEngineProvider { error("no media needed") })
        try {
            val t0 = System.currentTimeMillis()
            c.start()
            h.awaitConnected()
            val took = System.currentTimeMillis() - t0
            assertTrue(took < 1_600, "connected after ${took}ms; one after another is 2400ms")
        } finally {
            c.stop()
        }
    }
}
