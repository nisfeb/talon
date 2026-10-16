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
import kotlin.test.assertTrue

class DictationCardTest {
    private val zone = TimeZone.of("America/New_York")
    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime(2026, 10, d, h, m).toInstant(zone).toEpochMilliseconds()
    private fun meta(name: String, location: String = "") =
        JsonObject(mapOf("name" to JsonPrimitive(name), "location" to JsonPrimitive(location)))
    private fun event(name: String, l: Long, r: Long, location: String = "", all: Boolean = false) =
        CalendarRow(id = name, meta = meta(name, location), cat = if (all) "allday" else "timed", all = all, l = l, r = r)

    private val now = at(16, 12, 30)

    @Test
    fun `a day's events in order, all-day first, with times, places and what is on now`() {
        val rows = listOf(
            event("Rose's ballet", at(16, 16, 45), at(16, 19, 15), "Studio 5"),
            event("Core Sync", at(16, 12), at(16, 13)),
            event("Routing R&D", at(16, 10), at(16, 11)),
            event("Spain trip", LocalDateTime(2026, 10, 16, 0, 0).toInstant(TimeZone.UTC).toEpochMilliseconds(), LocalDateTime(2026, 10, 17, 0, 0).toInstant(TimeZone.UTC).toEpochMilliseconds(), all = true),
        )
        val days = DictationCards.days(rows, LocalDate(2026, 10, 16), LocalDate(2026, 10, 16), zone, now, twentyFour = false)
        assertEquals(1, days.size)
        assertEquals("Today", days[0].label)
        assertEquals(listOf("Spain trip", "Routing R&D", "Core Sync", "Rose's ballet"), days[0].events.map { it.name })
        val (trip, rnd, sync, ballet) = days[0].events
        assertEquals("All day", trip.time)
        assertEquals("10:00 AM – 11:00 AM", rnd.time)
        assertTrue(rnd.over && !rnd.now)
        assertTrue(sync.now && !sync.over)
        assertEquals("4:45 PM – 7:15 PM", ballet.time)
        assertEquals("Studio 5", ballet.place)
    }

    @Test
    fun `days with nothing on are left out, and named as a person would`() {
        val rows = listOf(event("Game", at(17, 14, 30), at(17, 16, 30)), event("Mass", at(19, 9), at(19, 10)))
        val days = DictationCards.days(rows, LocalDate(2026, 10, 16), LocalDate(2026, 10, 22), zone, now, twentyFour = true)
        assertEquals(listOf("Tomorrow", "Monday, Oct 19"), days.map { it.label })
        assertEquals("14:30 – 16:30", days[0].events.single().time)
    }

    @Test
    fun `tasks are the open ones, soonest due first, undated last, overdue marked`() {
        fun task(name: String, due: LocalDate?, done: Boolean = false) = CalendarTask(
            id = name, meta = JsonObject(mapOf("name" to JsonPrimitive(name))), cat = "todo", done = done,
            dueMs = due?.let { LocalDateTime(it.year, it.month, it.day, 0, 0).toInstant(TimeZone.UTC).toEpochMilliseconds() },
        )
        val tasks = DictationCards.tasks(
            listOf(
                task("Pack", LocalDate(2026, 10, 18)),
                task("Someday", null),
                task("Staples return", LocalDate(2026, 10, 14)),
                task("Done already", LocalDate(2026, 10, 15), done = true),
                task("Passport app", LocalDate(2026, 10, 16)),
            ),
            zone, now,
        )
        assertEquals(listOf("Staples return", "Passport app", "Pack", "Someday"), tasks.map { it.name })
        assertEquals(listOf("Oct 14", "Today", "Oct 18", null), tasks.map { it.due })
        assertEquals(listOf(true, false, false, false), tasks.map { it.overdue })
    }
}
