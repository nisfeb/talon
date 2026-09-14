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
}
