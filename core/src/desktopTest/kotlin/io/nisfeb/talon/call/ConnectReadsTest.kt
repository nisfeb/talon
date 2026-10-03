package io.nisfeb.talon.call

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
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

    // ~ricsul, too busy to send heartbeats, dropped every stream at 45 s,
    // and each reconnect read all six again: six of its slowest requests
    // a minute per device, for answers that had not changed.
    @Test
    fun `a stream the ship drops comes back without the six reads`() = runBlocking<Unit> {
        val h = TrunkHarness()
        val c = CallController(h.session, CallEngineProvider { error("no media needed") })
        try {
            c.start()
            h.await { h.streams.get() == 1 }
            assertEquals(6, h.scries.get())
            h.endStream()
            h.await(8_000) { h.streams.get() == 2 }
            assertEquals(6, h.scries.get(), "a reconnect seconds after the reads read them again")
        } finally {
            c.stop()
        }
    }

    // The pause went back to two seconds on being let in, so a stream
    // the ship dropped at once was asked for again every one to three
    // seconds for as long as it kept dropping them.
    @Test
    fun `a stream dropped at once is asked for again later each time`() = runBlocking<Unit> {
        val h = TrunkHarness()
        val c = CallController(h.session, CallEngineProvider { error("no media needed") })
        val at = mutableListOf<Long>()
        try {
            c.start()
            for (n in 1..4) {
                h.await(15_000) { h.streams.get() == n }
                at += System.currentTimeMillis()
                h.endStream()
            }
            val gap = at[3] - at[2]
            assertTrue(gap > 3_200, "the fourth stream came ${gap}ms after the third; backing off, it waits 4 to 12 s")
        } finally {
            c.stop()
        }
    }
}
