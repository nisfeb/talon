package io.nisfeb.talon.calendar

import io.nisfeb.talon.ui.screens.dueLabel
import io.nisfeb.talon.util.ClockStyle
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
 * What the evening review lays out: every open todo on a calendar the
 * owner can write that is late, due today where they are, or has no date,
 * by the rule orrery counts its push by.
 */
class EveningReviewTest {
    private val ny = TimeZone.of("America/New_York")

    /** 2026-10-29 23:00 in New York. */
    private val now = Instant.parse("2026-10-30T03:00:00Z").toEpochMilliseconds()

    private fun task(id: String, due: Long?, cal: String = "mine", done: Boolean = false, priority: Int? = null) =
        CalendarTask(id = id, cal = cal, cat = "todo", dueMs = due, done = done, priority = priority, meta = buildJsonObject { put("name", JsonPrimitive(id)) })
    private fun at(iso: String) = Instant.parse(iso).toEpochMilliseconds()
    private fun on(day: String) = LocalDate.parse(day).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

    private fun review(vararg t: CalendarTask) = eveningReview(t.toList(), setOf("mine"), now, ny)

    @Test
    fun `late, due today and undated each have their place, and the rest none`() {
        val r = review(
            task("dated yesterday", on("2026-10-28")),
            task("22:00 tonight", at("2026-10-30T02:00:00Z")),
            task("dated today", on("2026-10-29")),
            task("23:30 tonight", at("2026-10-30T03:30:00Z")),
            task("someday", null),
            task("dated tomorrow", on("2026-10-30")),
            task("01:00 tomorrow", at("2026-10-30T05:00:00Z")),
        )
        assertEquals(setOf("dated yesterday", "22:00 tonight"), r.overdue.map { it.id }.toSet())
        assertEquals(setOf("dated today", "23:30 tonight"), r.today.map { it.id }.toSet())
        assertEquals(listOf("someday"), r.undated.map { it.id })
        assertEquals("2 overdue, 2 due today, 1 with no date", r.line)
        assertFalse(r.isEmpty)
    }

    @Test
    fun `a time due this very minute is today's, not yet late`() {
        val r = review(task("now", now), task("a minute ago", now - 60_000))
        assertEquals(listOf("now"), r.today.map { it.id })
        assertEquals(listOf("a minute ago"), r.overdue.map { it.id })
    }

    @Test
    fun `done todos and calendars the owner cannot write stay out`() {
        val r = review(
            task("done", on("2026-10-28"), done = true),
            task("shared", on("2026-10-28"), cal = "theirs"),
            task("shared undated", null, cal = "theirs"),
        )
        assertTrue(r.isEmpty)
        assertEquals("", r.line)
    }

    @Test
    fun `most pressing first, then the earliest, then by name`() {
        val r = review(
            task("b plain", on("2026-10-20")),
            task("a plain", on("2026-10-20")),
            task("older plain", on("2026-10-10")),
            task("high", on("2026-10-27"), priority = 1),
            task("medium", on("2026-10-25"), priority = 5),
        )
        assertEquals(listOf("high", "medium", "older plain", "a plain", "b plain"), r.overdue.map { it.id })
    }

    @Test
    fun `the line leaves out what is none`() {
        assertEquals("1 overdue", review(task("x", on("2026-10-01"))).line)
        assertEquals("1 with no date", review(task("x", null)).line)
        assertEquals("1 due today", review(task("x", on("2026-10-29"))).line)
    }

    @Test
    fun `the day is the owner's, so the same todos in Tokyo are a day on`() {
        // 2026-10-30 12:00 in Tokyo: the 29th's are late there, the 30th's today.
        val r = eveningReview(listOf(task("29th", on("2026-10-29")), task("30th", on("2026-10-30"))), setOf("mine"), now, TimeZone.of("Asia/Tokyo"))
        assertEquals(listOf("29th"), r.overdue.map { it.id })
        assertEquals(listOf("30th"), r.today.map { it.id })
    }

    @Test
    fun `each row says its due as the owner reads it`() {
        val was = ClockStyle.twentyFourHour.value
        ClockStyle.twentyFourHour.value = true
        try {
            val today = LocalDate(2026, 10, 29)
            fun label(t: CalendarTask) = dueLabel(t, today, ny)
            assertEquals("Yesterday", label(task("x", on("2026-10-28"))))
            assertEquals("Today", label(task("x", on("2026-10-29"))))
            assertEquals("Tomorrow", label(task("x", on("2026-10-30"))))
            assertEquals("Oct 20", label(task("x", on("2026-10-20"))))
            assertEquals("22:00", label(task("x", at("2026-10-30T02:00:00Z"))), "a time today is the time alone, in New York")
            assertEquals("Yesterday, 09:00", label(task("x", at("2026-10-28T13:00:00Z"))))
            assertEquals(null, label(task("x", null)))
        } finally {
            ClockStyle.twentyFourHour.value = was
        }
    }
}
