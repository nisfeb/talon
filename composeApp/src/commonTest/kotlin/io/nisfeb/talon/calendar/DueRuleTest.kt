package io.nisfeb.talon.calendar

import io.nisfeb.talon.ui.CalendarRange
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A task due on a day, or at a moment (orrery 130 gives some a time), read
 * the way orrery counts them for the evening review: a day against today's
 * date where the owner is, a moment against now. Read as UTC dates, a task
 * due at 22:00 in New York sat on the next day and was not late.
 */
class DueRuleTest {
    private val ny = TimeZone.of("America/New_York")

    /** 2026-10-29 23:00 in New York. */
    private val now = Instant.parse("2026-10-30T03:00:00Z").toEpochMilliseconds()

    private fun at(iso: String) = task(iso, Instant.parse(iso).toEpochMilliseconds())
    private fun on(day: String) = task(day, LocalDate.parse(day).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds())
    private fun task(id: String, due: Long?) = CalendarTask(id = id, cat = "todo", dueMs = due, meta = buildJsonObject { put("name", JsonPrimitive(id)) })

    @Test
    fun `a day is a date, anything else a moment`() {
        assertTrue(on("2026-10-30").dueIsDate)
        assertFalse(at("2026-10-30T02:00:00Z").dueIsDate)
        assertFalse(task("none", null).dueIsDate)
        assertEquals(LocalDate(2026, 10, 30), on("2026-10-30").dueDate(ny), "a day keeps its own date anywhere")
        assertEquals(LocalDate(2026, 10, 29), at("2026-10-30T02:00:00Z").dueDate(ny), "22:00 in New York is the 29th there")
    }

    @Test
    fun `late, today and later, as orrery counts them`() {
        val gone = at("2026-10-30T02:00:00Z") // 22:00 on the 29th: an hour ago
        val tonight = at("2026-10-30T03:30:00Z") // 23:30 on the 29th
        val pastMidnight = at("2026-10-30T05:00:00Z") // 01:00 on the 30th
        assertTrue(gone.overdue(now, ny))
        assertFalse(gone.dueToday(now, ny), "late is not also today")
        assertTrue(tonight.dueToday(now, ny))
        assertFalse(tonight.overdue(now, ny))
        assertFalse(pastMidnight.dueToday(now, ny))
        assertFalse(pastMidnight.overdue(now, ny))
        // Late once gone by, as orrery's +evening-counts has it (< now): not this very minute.
        assertFalse(task("now", now).overdue(now, ny))
        assertTrue(task("now", now).dueToday(now, ny))
        assertTrue(on("2026-10-28").overdue(now, ny))
        assertTrue(on("2026-10-29").dueToday(now, ny), "today's date, though UTC has turned over")
        assertFalse(on("2026-10-29").overdue(now, ny))
        assertFalse(on("2026-10-30").dueToday(now, ny), "tomorrow where the owner is")
        assertFalse(task("none", null).overdue(now, ny) || task("none", null).dueToday(now, ny))
    }

    @Test
    fun `the task list puts each under the heading orrery would`() {
        val groups = groupTasks(
            listOf(at("2026-10-30T02:00:00Z"), at("2026-10-30T03:30:00Z"), at("2026-10-30T05:00:00Z"), on("2026-10-29"), on("2026-10-30"), task("none", null)),
            now, ny,
        ).associate { it.label to it.tasks.map { t -> t.id } }
        assertEquals(listOf("2026-10-30T02:00:00Z"), groups["Overdue"])
        assertEquals(setOf("2026-10-30T03:30:00Z", "2026-10-29"), groups["Today"].orEmpty().toSet())
        assertEquals(setOf("2026-10-30T05:00:00Z", "2026-10-30"), groups["Tomorrow"].orEmpty().toSet())
        assertEquals(listOf("none"), groups["No date"])
    }

    @Test
    fun `filters and the home page's today agree with it`() {
        val gone = at("2026-10-30T02:00:00Z")
        assertTrue(gone.inFilter(TaskFilter.OVERDUE, now, ny))
        assertTrue(gone.inFilter(TaskFilter.TODAY, now, ny), "Today takes in what is late, as before")
        assertFalse(at("2026-10-30T05:00:00Z").inFilter(TaskFilter.TODAY, now, ny))
        assertEquals(
            listOf("2026-10-29", "2026-10-30T02:00:00Z", "2026-10-30T03:30:00Z"),
            tasksInRange(listOf(at("2026-10-30T05:00:00Z"), at("2026-10-30T03:30:00Z"), gone, on("2026-10-29"), on("2026-10-30")), CalendarRange.REST_OF_DAY, now, ny).map { it.id },
        )
    }
}
