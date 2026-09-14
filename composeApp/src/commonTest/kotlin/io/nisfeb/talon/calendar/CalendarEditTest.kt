package io.nisfeb.talon.calendar

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CalendarEditTest {
    private val day = LocalDate(2026, 9, 14)

    @Test fun `a one-off timed event anchors on its wall clock, sent as utc`() {
        val b = eventBody(EventDraft(name = "Dentist", date = day, minuteOfDay = 14 * 60 + 30, durMin = 45, cal = "work"))
        assertEquals("add-event", b["action"]!!.jsonPrimitive.content)
        assertEquals("timed", b["cat"]!!.jsonPrimitive.content)
        assertEquals("once", b["kind"]!!.jsonPrimitive.content)
        assertEquals(1_789_396_200_000L, b["start_ms"]!!.jsonPrimitive.content.toLong())
        assertEquals("45", b["dur_min"]!!.jsonPrimitive.content)
        assertEquals("work", b["cal"]!!.jsonPrimitive.content)
        assertNull(b["count"])
    }

    @Test fun `a weekly event anchors on the day and carries its time and days in args`() {
        val b = eventBody(EventDraft(name = "Standup", date = day, minuteOfDay = 9 * 60, repeat = Repeat.WEEKLY,
            weekdays = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.MONDAY), count = 10), id = "e1")
        assertEquals("edit-event", b["action"]!!.jsonPrimitive.content)
        assertEquals(1_789_344_000_000L, b["start_ms"]!!.jsonPrimitive.content.toLong(), "midnight utc of the day")
        val args = b["args"]!!.jsonObject
        assertEquals("540", args["at"]!!.jsonPrimitive.content)
        assertEquals(listOf("mon", "wed"), args["days"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("10", b["count"]!!.jsonPrimitive.content)
    }

    @Test fun `a date event is a month and a day, nothing else`() {
        val b = eventBody(EventDraft(name = "Birthday", date = LocalDate(2026, 3, 9), cat = EventCat.DATE))
        assertEquals("3", b["month"]!!.jsonPrimitive.content)
        assertEquals("9", b["day"]!!.jsonPrimitive.content)
        assertNull(b["kind"])
    }

    @Test fun `an event round-trips through its json`() {
        val d = EventDraft(name = "Standup", note = "daily", date = day, minuteOfDay = 9 * 60 + 15, repeat = Repeat.WEEKLY,
            weekdays = setOf(DayOfWeek.FRIDAY), count = 4, cal = "default")
        val json = eventBody(d, "e1").let { it.jsonObject }
        // event.json carries the same fields the poke does, plus id.
        val back = draftFromEvent(json, day)!!
        assertEquals(d.name, back.name)
        assertEquals(d.minuteOfDay, back.minuteOfDay)
        assertEquals(d.weekdays, back.weekdays)
        assertEquals(d.count, back.count)
        assertEquals(d.date, back.date)
    }

    @Test fun `the month grid starts on the monday on or before the first`() {
        val g = monthGrid(2026, 9)
        assertEquals(42, g.size)
        assertEquals(LocalDate(2026, 8, 31), g.first(), "1 Sep 2026 is a Tuesday")
        assertEquals(LocalDate(2026, 9, 1), g[1])
    }

    @Test fun `an all-day row spans its days and ends before r`() {
        val row = CalendarRow(id = "t", all = true, l = 1_789_344_000_000L, r = 1_789_344_000_000L + 2 * 86_400_000L)
        assertEquals(listOf(LocalDate(2026, 9, 14), LocalDate(2026, 9, 15)), daysOf(row, TimeZone.UTC))
    }
}
