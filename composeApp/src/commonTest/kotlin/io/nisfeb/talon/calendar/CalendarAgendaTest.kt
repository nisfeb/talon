package io.nisfeb.talon.calendar

import io.nisfeb.talon.ui.CalendarRange
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals

class CalendarAgendaTest {
    private val h = 60 * 60 * 1000L
    // 2026-09-14 10:00 UTC
    private val now = 1_789_380_000_000L
    private fun row(id: String, l: Long, r: Long) = CalendarRow(id = id, l = l, r = r)
    private val rows = listOf(
        row("past", now - 3 * h, now - 2 * h),
        row("ongoing", now - 1 * h, now + 1 * h),
        row("soon", now + 2 * h, now + 3 * h),
        row("tonight", now + 10 * h, now + 11 * h),
        row("nextweek", now + 7 * 24 * h, now + 7 * 24 * h + h),
    )
    private val utc = TimeZone.UTC

    @Test fun `what is under way counts, what is over does not`() {
        assertEquals(listOf("ongoing", "soon"), agenda(rows, CalendarRange.NEXT_3_HOURS, now, utc).map { it.id })
    }

    @Test fun `next only is the first live one however far off`() {
        assertEquals(listOf("ongoing"), agenda(rows, CalendarRange.NEXT_ONLY, now, utc).map { it.id })
        assertEquals(listOf("nextweek"), agenda(listOf(rows.last()), CalendarRange.NEXT_ONLY, now, utc).map { it.id })
    }

    @Test fun `rest of today ends at midnight in the zone`() {
        // 10:00 UTC: tonight (20:00) is today; next week is not.
        assertEquals(listOf("ongoing", "soon", "tonight"), agenda(rows, CalendarRange.REST_OF_DAY, now, utc).map { it.id })
        // In a zone eleven hours ahead it is already 21:00, so 20:00 UTC (07:00 tomorrow there) is not today.
        assertEquals(listOf("ongoing", "soon"), agenda(rows, CalendarRange.REST_OF_DAY, now, TimeZone.of("Pacific/Noumea")).map { it.id })
    }

    @Test fun `a window row parses with its meta`() {
        val w = io.nisfeb.talon.mail.AuspexApi.json.decodeFromString(
            CalendarWindow.serializer(),
            """{"caps":[],"rows":[{"id":"e1","cal":"default","idx":3,"meta":{"name":"Dentist","color":"#abc","location":"Town"},"cat":"timed","kind":"once","all":false,"l":1,"r":2}]}""",
        )
        val r = w.rows.single()
        assertEquals("Dentist", r.name)
        assertEquals("Town", r.location)
        assertEquals("#abc", r.color)
        assertEquals(false, r.all)
    }

    private fun task(id: String, due: Long?, done: Boolean = false) =
        CalendarTask(id = id, cat = "todo", dueMs = due, done = done, meta = kotlinx.serialization.json.buildJsonObject { put("name", kotlinx.serialization.json.JsonPrimitive(id)) })

    @Test fun `tasks go soonest due first, undated last, and only open ones due by today nag`() {
        val day = 24 * h
        val today = kotlinx.datetime.LocalDate(2026, 9, 14)
        val tomorrow = 1_789_430_400_000L
        val ts = listOf(task("b-undated", null), task("a-undated", null), task("late", tomorrow - 3 * day), task("today", tomorrow - day), task("soon", tomorrow), task("done-late", tomorrow - day, done = true))
        assertEquals(listOf("late", "done-late", "today", "soon", "a-undated", "b-undated"), taskOrder(ts).map { it.id }, "same day: by name")
        // 2026-09-14 22:00 UTC: the rest of today stops at midnight; three hours cross it.
        val evening = 1_789_423_200_000L
        assertEquals(listOf("late", "today"), tasksInRange(ts, CalendarRange.REST_OF_DAY, evening, utc).map { it.id })
        assertEquals(listOf("late", "today", "soon"), tasksInRange(ts, CalendarRange.NEXT_3_HOURS, evening, utc).map { it.id })
        assertEquals(listOf("late", "today"), tasksInRange(ts, CalendarRange.NEXT_3_HOURS, evening - 6 * h, utc).map { it.id }, "at 16:00 it does not")
        assertEquals(listOf("late", "today", "soon"), tasksInRange(ts, CalendarRange.NEXT_DAY, evening, utc).map { it.id })
        assertEquals(listOf("late", "today"), tasksInRange(ts, CalendarRange.NEXT_ONLY, evening, utc).map { it.id })
        assertEquals(today, task("today", tomorrow - day).dueDate())
    }

    @Test fun `next only at exactly utc midnight still means today`() {
        // 2026-09-14 00:00:00 UTC: nowMs - 1 would be yesterday, but "next only" has no end to subtract from.
        val midnight = 1_789_344_000_000L
        val ts = listOf(task("today", midnight), task("tomorrow", midnight + 24 * h))
        assertEquals(listOf("today"), tasksInRange(ts, CalendarRange.NEXT_ONLY, midnight, utc).map { it.id })
    }

    @Test fun `a day's window stops calling itself today`() {
        assertEquals("Coming Up", agendaHeading(CalendarRange.NEXT_DAY))
        for (r in CalendarRange.entries.filter { it != CalendarRange.NEXT_DAY }) {
            assertEquals("Today", agendaHeading(r), r.name)
        }
    }

    @Test fun `the line falls where the day turns over, and not above what is running`() {
        val today = agendaDay(now, now, utc)
        // Yesterday evening, still going: today's, so no line above it.
        assertEquals(today, agendaDay(now - 20 * h, now, utc))
        assertEquals(today, agendaDay(now + 2 * h, now, utc))
        // The first thing after midnight is the one the line goes above.
        assertEquals(today.plus(1, DateTimeUnit.DAY), agendaDay(now + 20 * h, now, utc))
        // The zone decides when the day turns, not UTC.
        val noumea = TimeZone.of("Pacific/Noumea")
        assertEquals(agendaDay(now, now, noumea).plus(1, DateTimeUnit.DAY), agendaDay(now + 9 * h, now, noumea))
    }
}
