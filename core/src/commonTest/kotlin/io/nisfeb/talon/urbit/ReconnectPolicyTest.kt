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

    // ~ricsul broke its streams every few minutes and every break re-ran
    // the whole pass, unread scry and all, until the ship did nothing else.
    @Test
    fun `a reconnect after a short outage re-subscribes, a long one reconciles`() {
        val now = 10_000_000L
        val lastPass = now - 5 * 60_000L
        assertFalse(shouldBootstrap(false, lastPass, now, lastHeardMs = now - 20_000L), "stream broke 20 s ago")
        assertTrue(shouldBootstrap(false, lastPass, now, lastHeardMs = now - 10 * 60_000L), "ten minutes without a word")
        assertTrue(shouldBootstrap(false, lastPass, now), "not known when it last heard: reconcile")
    }

    @Test
    fun `short outages still reconcile every fifteen minutes`() {
        val now = 10_000_000L
        assertFalse(shouldBootstrap(false, now - (RECONCILE_EVERY_MS - 1), now, lastHeardMs = now - 5_000L))
        assertTrue(shouldBootstrap(false, now - RECONCILE_EVERY_MS, now, lastHeardMs = now - 5_000L))
        assertFalse(shouldBootstrap(false, now - 30_000L, now, lastHeardMs = now - 10 * 60_000L), "never twice within a minute")
    }

    @Test
    fun `a session that never reconciled always does`() {
        assertTrue(shouldBootstrap(firstRun = false, lastBootstrapMs = 0L, nowMs = 1_000L))
    }

    // Fifty posts from every channel on every launch, a full store or not.
    @Test
    fun `the pause goes back to the first only after a stream that lived`() {
        assertEquals(2_000L, pauseAfterStream(16_000L, livedMs = 30_000L, first = 2_000L, healthyMs = 30_000L))
        assertEquals(16_000L, pauseAfterStream(16_000L, livedMs = 29_999L, first = 2_000L, healthyMs = 30_000L), "dropped sooner")
        assertEquals(16_000L, pauseAfterStream(16_000L, livedMs = null, first = 2_000L, healthyMs = 30_000L), "never opened")
    }

    @Test
    fun `the deep history pass runs on an empty store or after a day away, not otherwise`() {
        val day = 24 * 60 * 60 * 1000L
        assertTrue(needsDeepHistory(null, nowMs = 10 * day))
        assertTrue(needsDeepHistory(newestSentMs = 8 * day, nowMs = 10 * day))
        assertFalse(needsDeepHistory(newestSentMs = 10 * day - 60_000, nowMs = 10 * day))
        assertFalse(needsDeepHistory(newestSentMs = 9 * day, nowMs = 10 * day), "exactly a day: not yet")
    }

    // Tlon's client reads only what changed while its last read is under
    // three days old, and everything again past that.
    @Test
    fun `a catch-up reads changes while the last read is under three days old`() {
        val now = 10 * CHANGES_MAX_AGE_MS
        assertFalse(useChanges(0L, now, unserved = false), "no read yet: init-posts")
        assertTrue(useChanges(now - 60_000L, now, unserved = false))
        assertTrue(useChanges(now - CHANGES_MAX_AGE_MS + 1, now, unserved = false))
        assertFalse(useChanges(now - CHANGES_MAX_AGE_MS, now, unserved = false), "three days: read it all")
        assertFalse(useChanges(now - 60_000L, now, unserved = true), "a ship without /changes")
    }

    @Test
    fun `a dropped watch is watched again, unless it was dropped a moment ago`() {
        val now = 1_000_000L
        assertTrue(resubscribeAfterQuit(null, now))
        assertTrue(resubscribeAfterQuit(now - QUIT_AGAIN_MS, now))
        assertFalse(resubscribeAfterQuit(now - QUIT_AGAIN_MS + 1, now))
        assertFalse(resubscribeAfterQuit(now, now))
    }

    @Test
    fun `vere's healthz answer reads as busy, down or out of reach`() {
        assertEquals(ShipHealth.IDLE, shipHealthOf(204))
        assertEquals(ShipHealth.BUSY, shipHealthOf(429))
        listOf(502, 503, 504).forEach { assertEquals(ShipHealth.DOWN, shipHealthOf(it), "$it") }
        assertEquals(ShipHealth.UNREACHABLE, shipHealthOf(null))
        // A redirect to a login page, an old vere's 404: not a word about the ship.
        listOf(200, 302, 404).forEach { assertEquals(ShipHealth.UNKNOWN, shipHealthOf(it), "$it") }
    }
}
