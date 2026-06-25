package io.nisfeb.talon.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoopScheduleTest {

    private val min = 60_000L

    @Test
    fun `next fire is one interval after last run`() {
        assertEquals(30 * min, LoopSchedule.nextFireMs(lastRunMs = 0, intervalMinutes = 30))
        assertEquals(1_000_000L + 60 * min, LoopSchedule.nextFireMs(1_000_000L, 60))
    }

    @Test
    fun `interval floor is enforced`() {
        // 5 min is below the floor → treated as the 15-min minimum.
        assertEquals(15 * min, LoopSchedule.nextFireMs(lastRunMs = 0, intervalMinutes = 5))
    }

    @Test
    fun `isDue flips exactly at the next fire time`() {
        assertFalse(LoopSchedule.isDue(now = 29 * min, lastRunMs = 0, intervalMinutes = 30))
        assertTrue(LoopSchedule.isDue(now = 30 * min, lastRunMs = 0, intervalMinutes = 30))
        assertTrue(LoopSchedule.isDue(now = 31 * min, lastRunMs = 0, intervalMinutes = 30))
    }
}
