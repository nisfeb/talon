package io.nisfeb.talon.calendar

import io.nisfeb.talon.ui.CalendarRange
import kotlinx.datetime.TimeZone
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
}
