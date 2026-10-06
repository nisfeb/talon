package io.nisfeb.talon.calendar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A calendar reminder push names its occurrence in its tag, as the
 * calendar app writes it (lib/calendar-core.hoon +lead-pushes and
 * +alarm-pushes): `cal-<id>-<idx>`, and `-<n>` more for an alarm.
 */
class CalendarPushTagTest {
    private fun row(id: String, idx: Int = 0) = CalendarRow(id = id, idx = idx, l = 0, r = 0)
    private val rows = listOf(row("0v1a.2b3c"), row("s1", 3), row("abc-123@google.com", 0), row("abc-123@google.com", 1))

    @Test
    fun `a lead push names the occurrence`() {
        assertEquals(row("s1", 3), calendarRowOfPushTag("cal-s1-3", rows))
        assertEquals(row("0v1a.2b3c"), calendarRowOfPushTag("cal-0v1a.2b3c-0", rows))
    }

    @Test
    fun `an alarm push names it too, with its alarm after`() {
        assertEquals(row("s1", 3), calendarRowOfPushTag("cal-s1-3-0", rows))
    }

    // An ICS uid has dashes of its own: splitting the tag would cut it.
    @Test
    fun `an id with dashes is found whole`() {
        assertEquals(row("abc-123@google.com", 1), calendarRowOfPushTag("cal-abc-123@google.com-1", rows))
        assertEquals(row("abc-123@google.com", 0), calendarRowOfPushTag("cal-abc-123@google.com-0-2", rows))
    }

    @Test
    fun `other pushes and unknown occurrences are not the calendar's`() {
        assertNull(calendarRowOfPushTag("orrery-leave-situation/opti@1", rows))
        assertNull(calendarRowOfPushTag("cal-s1-4", rows))
        assertNull(calendarRowOfPushTag("cal-s1-3-", rows))
        assertNull(calendarRowOfPushTag(null, rows))
    }
}
