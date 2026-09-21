package io.nisfeb.talon.call

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The route the owner picked, against the two ways of getting it wrong:
 * a call that drops for a moment coming back on the earpiece, and a
 * call hours later opening on speaker in public.
 */
class RouteMemoryTest {
    private val now = 1_800_000_000_000L

    @Test
    fun `a pick made while a session is up is in force`() {
        assertTrue(routeSurvives(quietSinceMs = 0L, nowMs = now), "nothing has ended")
    }

    @Test
    fun `a reconnect keeps it, and the walk to the next call does not`() {
        // Six tries doubling from a second is about a minute of gap.
        assertTrue(routeSurvives(now - 63_000L, now), "a line rejoining is the same call")
        assertTrue(routeSurvives(now - REMEMBER_MS, now), "right up to the edge")
        assertFalse(routeSurvives(now - REMEMBER_MS - 1, now))
        assertFalse(routeSurvives(now - 3 * 3_600_000L, now), "a call this afternoon is not that one")
    }
}
