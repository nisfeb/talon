package io.nisfeb.talon.urbit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two reconnect rules, from the ~ricsul-bilwyt saturation: a
 * reconnect must be cheap, and backoff must be jittered so a pier
 * restart does not bring every client back on the same tick.
 */
class ReconnectPolicyTest {
    @Test
    fun `jitter stays within half to one and a half, and spreads`() {
        val samples = List(500) { jittered(10_000L) }
        assertTrue(samples.all { it in 5_000L..15_000L }, "out of range: ${samples.minOrNull()}..${samples.maxOrNull()}")
        assertTrue(samples.distinct().size > 100, "barely spread: ${samples.distinct().size} distinct")
        // Never zero: a zero wait is a hot loop.
        assertTrue(jittered(1L) >= 1L)
        assertTrue(jittered(0L) >= 1L)
    }

    @Test
    fun `the first connect always reconciles`() {
        assertTrue(shouldBootstrap(firstRun = true, lastBootstrapMs = 0L, nowMs = 1_000_000L))
        // Even if one just ran: an app start must reconcile.
        assertTrue(shouldBootstrap(firstRun = true, lastBootstrapMs = 999_000L, nowMs = 1_000_000L))
    }

    @Test
    fun `a reconnect right after a pass only re-subscribes`() {
        val now = 1_000_000L
        assertFalse(shouldBootstrap(false, now - 1_000L, now), "1s later")
        assertFalse(shouldBootstrap(false, now - 59_000L, now), "59s later")
        assertTrue(shouldBootstrap(false, now - BOOTSTRAP_MIN_GAP_MS, now), "at the window")
        assertTrue(shouldBootstrap(false, now - 300_000L, now), "5 min later")
    }

    @Test
    fun `a session that never reconciled always does`() {
        assertTrue(shouldBootstrap(firstRun = false, lastBootstrapMs = 0L, nowMs = 1_000L))
    }

    @Test
    fun `the window is a minute`() {
        assertEquals(60_000L, BOOTSTRAP_MIN_GAP_MS)
    }
}
