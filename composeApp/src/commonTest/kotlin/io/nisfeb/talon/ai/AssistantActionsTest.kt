package io.nisfeb.talon.ai

import io.nisfeb.talon.data.ContactEntity
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AssistantActionsTest {
    private fun c(ship: String, nick: String? = null) = ContactEntity(ship = ship, nickname = nick, bio = null, avatarUrl = null)

    @Test fun `a nickname, a partial and an @p all find the person, exact first`() {
        val book = listOf(c("~sampel-palnet", "Sunbum"), c("~zod", "Sunny"), c("~marzod"))
        assertEquals("~sampel-palnet", findPeople("sunbum", book).first().ship)
        assertEquals(listOf("~sampel-palnet", "~zod"), findPeople("sun", book).map { it.ship })
        assertEquals("~marzod", findPeople("~marzod", book).single().ship)
        assertTrue(findPeople("nobody", book).isEmpty())
    }

    @Test fun `dates and clocks are strict`() {
        assertEquals(19, parseDate("2026-09-19")!!.dayOfMonth)
        assertNull(parseDate("Saturday"))
        assertEquals(12 * 60 + 30, parseClock("12:30"))
        assertNull(parseClock("25:00"))
        assertNull(parseClock(null))
    }

    @Test fun `the now line names the day and the zone`() {
        // 2026-09-14 10:00 UTC is a Monday.
        val line = nowLine(TimeZone.UTC, 1_789_380_000_000L)
        assertTrue(line.startsWith("NOW: 2026-09-14 (Monday) 10:00, zone UTC"), line)
    }

    @Test fun `an event posted by the assistant carries a card that decodes back`() {
        // 2026-09-19 00:00 UTC, a Saturday.
        val sat = 1_789_776_000_000L
        assertEquals("Sat 19 Sep 2026 · all day", eventWhenLine(sat, sat + 86_400_000L, allDay = true, zone = TimeZone.of("America/New_York")))
        assertEquals("Sat 19 Sep 2026 · 3 days", eventWhenLine(sat, sat + 3 * 86_400_000L, allDay = true, zone = TimeZone.UTC))
        val lunch = sat + 12 * 3_600_000L + 30 * 60_000L
        assertTrue(eventWhenLine(lunch, lunch + 3_600_000L, allDay = false, zone = TimeZone.UTC).startsWith("Sat 19 Sep 2026 · 12:30"))
        val msg = io.nisfeb.talon.calendar.eventCardMessage("Lunch", "Sat 19 Sep 2026 · 12:30 PM–1:30 PM", "The Oak", "", emptyList(), lunch, lunch + 3_600_000L)
        assertEquals(io.nisfeb.talon.ui.DecodedCal(lunch, lunch + 3_600_000L, "Lunch"), io.nisfeb.talon.ui.decodeCalTag(msg))
        assertTrue(msg.startsWith("📅 Lunch\n"), msg)
    }

    @Test fun `an event line says when, what, repeats, where, tags and calendar by name`() {
        val sat = 1_789_776_000_000L
        val lunch = sat + 12 * 3_600_000L + 30 * 60_000L
        val p = { v: String -> kotlinx.serialization.json.JsonPrimitive(v) }
        val row = io.nisfeb.talon.calendar.CalendarRow(
            id = "e1", cal = "fam", cat = "timed", kind = "weekly", l = lunch, r = lunch + 3_600_000L,
            meta = kotlinx.serialization.json.JsonObject(mapOf("name" to p("Lunch"), "location" to p("The Oak"), "tags" to kotlinx.serialization.json.JsonArray(listOf(p("food"))))),
        )
        assertEquals("event=e1 2026-09-19 12:30–13:30 Lunch (repeats weekly) @ The Oak #food (calendar Family)", describeRow(row, TimeZone.UTC, mapOf("fam" to "Family")))
        val fair = io.nisfeb.talon.calendar.CalendarRow(
            id = "e2", cat = "allday", all = true, l = sat, r = sat + 2 * 86_400_000L,
            meta = kotlinx.serialization.json.JsonObject(mapOf("name" to p("Fair"))),
        )
        assertEquals("event=e2 2026-09-19 all day (2 days) Fair (calendar default)", describeRow(fair, TimeZone.of("America/New_York"), emptyMap()))
    }
}
