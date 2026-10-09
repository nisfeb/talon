package io.nisfeb.talon.calendar

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The assistant's reminder words as the calendar's alarms, in the shapes
 * the calendar's own parser takes (lib/calendar-core parse-alarm:
 * before with whole seconds s; offset with from start or end, after a
 * boolean, s). An all-day day reached the owner with no reminder at all
 * (orrery-0c's review, 2026-10-09).
 */
class ReminderAlarmsTest {
    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun raw(text: String, allDay: Boolean) = reminderAlarms(text, allDay).map { it.raw }

    @Test
    fun `a span is that long before the start`() {
        assertEquals(listOf(json("""{"kind":"before","s":1800,"desc":""}""")), raw("30m", allDay = false))
        assertEquals(listOf(json("""{"kind":"before","s":7200,"desc":""}""")), raw("2h", allDay = false))
        assertEquals(listOf(json("""{"kind":"before","s":86400,"desc":""}""")), raw("1d", allDay = true))
        assertEquals(listOf(json("""{"kind":"before","s":900,"desc":""}""")), raw("15 min", allDay = false))
        assertEquals(listOf(json("""{"kind":"before","s":172800,"desc":""}""")), raw("2 days", allDay = true))
    }

    @Test
    fun `the morning of an all-day event is the calendar form's own offset`() {
        // The calendar form's "the morning of (9:00)" for a non-timed event.
        assertEquals(listOf(json("""{"kind":"offset","from":"start","after":true,"s":32400,"desc":""}""")), raw("morning", allDay = true))
        assertEquals(listOf(json("""{"kind":"offset","from":"start","after":true,"s":30600,"desc":""}""")), raw("08:30", allDay = true))
        assertEquals(2, raw("morning, 1d", allDay = true).size, "several, in order")
    }

    @Test
    fun `none is none, and what does not read says so`() {
        assertTrue(raw("none", allDay = true).isEmpty())
        assertTrue(raw(" ", allDay = true).isEmpty())
        val timed = assertFailsWith<IllegalArgumentException> { reminderAlarms("morning", allDay = false) }
        assertTrue("for a timed one say how long before" in timed.message.orEmpty(), timed.message)
        val nonsense = assertFailsWith<IllegalArgumentException> { reminderAlarms("soon", allDay = true) }
        assertTrue("30m, 2h, 1d, morning" in nonsense.message.orEmpty(), nonsense.message)
        assertFailsWith<IllegalArgumentException> { reminderAlarms("25:00", allDay = true) }
    }
}
