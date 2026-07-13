package io.nisfeb.talon.ai

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoopScheduleTest {

    private val min = 60_000L
    private val hour = 3_600_000L
    private val day = 86_400_000L
    // Epoch 0 is Thursday 1970-01-01 00:00 UTC — anchor the weekly math.
    private val utc = TimeZone.UTC

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

    @Test
    fun `weekly picks the next matching weekday at the set time`() {
        // "6am on Sunday", starting from Thu 1970-01-01 00:00 UTC.
        // First Sunday is Jan 4 (3 days on), at 06:00.
        val sundayBit = LoopSchedule.dayBit(DayOfWeek.SUNDAY)
        assertEquals(
            3 * day + 6 * hour,
            LoopSchedule.nextWeeklyFireMs(0, atMinuteOfDay = 360, daysMask = sundayBit, zone = utc),
        )
    }

    @Test
    fun `empty day mask fires every day, same day if time is still ahead`() {
        // Thu 00:00, 06:00 daily → same day 06:00.
        assertEquals(6 * hour, LoopSchedule.nextWeeklyFireMs(0, 360, 0, utc))
        // Thu 07:00 (past today's 06:00) → next day 06:00.
        assertEquals(day + 6 * hour, LoopSchedule.nextWeeklyFireMs(7 * hour, 360, 0, utc))
    }
}
