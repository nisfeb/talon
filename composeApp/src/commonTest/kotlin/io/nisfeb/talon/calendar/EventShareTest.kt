package io.nisfeb.talon.calendar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EventShareTest {
    @Test fun `the invite file carries utc moments for a timed event and dates for a day`() {
        val timed = eventIcs("e1", "Dentist; 2pm", "1 Main St, town", "bring\nthe form", 1_789_396_200_000L, 1_789_398_900_000L, allDay = false)
        assertTrue("DTSTART:20260914T143000Z" in timed, timed)
        assertTrue("DTEND:20260914T151500Z" in timed)
        assertTrue("SUMMARY:Dentist\\; 2pm" in timed)
        assertTrue("LOCATION:1 Main St\\, town" in timed)
        assertTrue("DESCRIPTION:bring\\nthe form" in timed)
        assertTrue("UID:e1@talon" in timed)
        val day = eventIcs("e2", "Fair", "", "", 1_789_344_000_000L, 1_789_430_400_000L, allDay = true)
        assertTrue("DTSTART;VALUE=DATE:20260914" in day)
        assertTrue("DTEND;VALUE=DATE:20260915" in day)
    }

    @Test fun `the message says what, when, where`() {
        assertEquals("📅 Lunch\nSat 19 Sep 2026 · 12:30–13:30\nWhere: The Oak\n#food", eventShareText("Lunch", "Sat 19 Sep 2026 · 12:30–13:30", "The Oak", "", listOf("food")))
    }

    @Test fun `a line break in the id cannot smuggle a line into the file`() {
        val ics = eventIcs("e1\r\nATTENDEE:mailto:a@b.c", "Dentist", "", "", 1_789_396_200_000L, 1_789_398_900_000L, allDay = false)
        assertTrue(ics.lines().none { it.startsWith("ATTENDEE") }, ics)
        assertTrue("UID:e1ATTENDEE:mailto:a@b.c@talon" in ics, "the break is dropped, the rest of the id kept")
    }

    @Test fun `a carriage return in the name is escaped, not written raw`() {
        val ics = eventIcs("e1", "bring\rthe form", "", "", 1_789_396_200_000L, 1_789_398_900_000L, allDay = false)
        assertEquals("SUMMARY:bring\\rthe form", ics.lines().single { it.startsWith("SUMMARY") })
    }

    @Test fun `the stamp is when the file was made, not when the event starts`() {
        val ics = eventIcs("e1", "Dentist", "", "", 1_789_396_200_000L, 1_789_398_900_000L, allDay = false)
        val stamp = Regex("""DTSTAMP:(\d{8}T\d{6}Z)""").find(ics)?.groupValues?.get(1)
        assertTrue(stamp != null, "a DTSTAMP in iCalendar form: $ics")
        assertTrue("DTSTART:20260914T143000Z" in ics)
        assertNotEquals("20260914T143000Z", stamp, "the event's start is not the file's birthday")
    }
}
