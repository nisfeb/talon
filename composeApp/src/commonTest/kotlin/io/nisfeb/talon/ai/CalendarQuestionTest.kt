package io.nisfeb.talon.ai

import io.nisfeb.talon.calendar.CalendarRow
import io.nisfeb.talon.calendar.CalendarTask
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CalendarQuestionTest {
    // Friday, 16 October 2026.
    private val today = LocalDate(2026, 10, 16)
    private val zone = TimeZone.of("America/New_York")
    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime(2026, 10, d, h, m).toInstant(zone).toEpochMilliseconds()
    private val now = at(16, 12, 30)

    private fun span(text: String, earlier: String? = null) = CalendarQuestion.parse(text, today, earlier)

    @Test
    fun `plain questions about what is on, and their days`() {
        assertEquals(CalendarQuestion.Span(today, today, "today"), span("What's on my calendar today?"))
        assertEquals(CalendarQuestion.Span(today, today, "today"), span("What's on my calendar?"))
        assertEquals(CalendarQuestion.Span(today, today, "today"), span("What does my day look like?"))
        assertEquals(CalendarQuestion.Span(LocalDate(2026, 10, 17), LocalDate(2026, 10, 17), "tomorrow"), span("What do I have tomorrow?"))
        assertEquals(CalendarQuestion.Span(today, LocalDate(2026, 10, 22), "this week"), span("What's on my calendar this week?"))
        assertEquals(CalendarQuestion.Span(LocalDate(2026, 10, 19), LocalDate(2026, 10, 25), "next week"), span("What's happening next week?"))
        assertEquals(CalendarQuestion.Span(LocalDate(2026, 10, 17), LocalDate(2026, 10, 18), "this weekend"), span("Do I have anything this weekend?"))
        assertEquals(CalendarQuestion.Span(LocalDate(2026, 10, 20), LocalDate(2026, 10, 20), "on Tuesday"), span("What's on Tuesday?"))
        assertEquals(CalendarQuestion.Span(today, today, "today"), span("What's on Friday?"))
        assertEquals(CalendarQuestion.Span(LocalDate(2026, 10, 23), LocalDate(2026, 10, 23), "on Friday"), span("What do I have next Friday?"))
        assertEquals(true, span("What's next?")?.next)
    }

    @Test
    fun `anything more than what is on goes to the model`() {
        assertNull(span("What's on my calendar with Andrea this week?"))
        assertNull(span("When is Linus's game?"))
        assertNull(span("Am I free tomorrow afternoon?"))
        assertNull(span("How many meetings do I have this week?"))
        assertNull(span("Add the dentist on Tuesday."))
        assertNull(span("What did Andrea say today?"))
        assertNull(span("Do I have any new mail?"))
    }

    @Test
    fun `a word the card cannot answer sends it to the model`() {
        // sneagan, 2026-10-16: each was answered with the day's events.
        assertNull(span("What do I have left to do before Barcelona?"))
        assertNull(span("What do I have to get done before I go?", earlier = "When do I arrive in Barcelona?"))
        assertNull(span("When do I arrive in Barcelona?", earlier = "What's on my calendar today?"))
        assertNull(span("What do I have to do today?"))
        assertNull(span("What's next before the trip?"))
        // Filler is still a plain question.
        assertEquals(LocalDate(2026, 10, 17), span("Hey Talon, what's on for tomorrow?")?.from)
        assertEquals(today, span("Okay, what's my schedule look like?")?.from)
    }

    @Test
    fun `a bare follow-up to a calendar question asks it for other days`() {
        assertEquals(LocalDate(2026, 10, 17), span("And tomorrow?", earlier = "What's on my calendar today?")?.from)
        assertNull(span("And tomorrow?"))
        assertNull(span("And tomorrow?", earlier = "Who is Andrea?"))
    }

    private fun event(name: String, l: Long, r: Long) = CalendarRow(id = name, meta = JsonObject(mapOf("name" to JsonPrimitive(name))), l = l, r = r)
    private fun task(name: String, due: LocalDate) = CalendarTask(
        id = name, meta = JsonObject(mapOf("name" to JsonPrimitive(name))), cat = "todo",
        dueMs = LocalDateTime(due.year, due.month, due.day, 0, 0).toInstant(TimeZone.UTC).toEpochMilliseconds(),
    )

    private val week = listOf(
        event("Routing R&D", at(16, 10), at(16, 11)),
        event("Core Sync", at(16, 12), at(16, 13)),
        event("Ballet", at(16, 16, 45), at(16, 19, 15)),
        event("Linus's game", at(17, 14, 30), at(17, 16, 30)),
        event("Mass", at(18, 9), at(18, 10)),
    )

    private fun say(span: CalendarQuestion.Span, tasks: List<CalendarTask> = emptyList()) =
        CalendarQuestion.answer(span, week, tasks, zone, now, twentyFour = false)

    @Test
    fun `today says how many, what is on now and what is next`() {
        assertEquals(
            "Today you have 3 events. You're in Core Sync until 1:00 PM. Next is Ballet at 4:45 PM. One task is due.",
            say(span("What's on today?")!!, listOf(task("Passport app", today))),
        )
    }

    @Test
    fun `a week says how many over how many days, and what is next`() {
        assertEquals("This week: 5 events over 3 days. You're in Core Sync until 1:00 PM. Next is Ballet today at 4:45 PM.", say(span("What's on this week?")!!))
    }

    @Test
    fun `a day ahead says what comes first, and nothing is nothing`() {
        assertEquals("Tomorrow you have one event. First is Linus's game at 2:30 PM.", say(span("What do I have tomorrow?")!!))
        assertEquals("Nothing on your calendar on Tuesday.", say(span("What's on Tuesday?")!!))
        assertEquals("Next up, Ballet today at 4:45 PM.", say(span("What's next?")!!))
    }

    @Test
    fun `an overdue task counts on a span from today, and an all-day first event is all day`() {
        val overdue = task("Staples return", LocalDate(2026, 10, 14))
        assertEquals(
            "Today you have 3 events. You're in Core Sync until 1:00 PM. Next is Ballet at 4:45 PM. One task is due.",
            say(span("What's on today?")!!, listOf(overdue)),
        )
        val trip = CalendarRow(
            id = "trip", meta = JsonObject(mapOf("name" to JsonPrimitive("Spain trip"))), cat = "allday", all = true,
            l = LocalDateTime(2026, 10, 20, 0, 0).toInstant(TimeZone.UTC).toEpochMilliseconds(),
            r = LocalDateTime(2026, 10, 21, 0, 0).toInstant(TimeZone.UTC).toEpochMilliseconds(),
        )
        assertEquals(
            "On Tuesday you have one event. First is Spain trip all day.",
            CalendarQuestion.answer(span("What's on Tuesday?")!!, listOf(trip), emptyList(), zone, now, false),
        )
    }
}
