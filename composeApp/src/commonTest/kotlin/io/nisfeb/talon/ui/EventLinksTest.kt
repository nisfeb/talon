package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class EventLinksTest {
    @Test fun `phone numbers are found as written and dialled as digits`() {
        val text = "Dr Vane, +1 (212) 555-0147, back door. Ext 4402 is not a number; 2026-09-15 is a date. Also 0161 496 0000."
        assertEquals(listOf("+1 (212) 555-0147", "0161 496 0000"), phoneNumbersIn(text))
        assertEquals("tel:+12125550147", telUri("+1 (212) 555-0147"))
        assertEquals(emptyList(), phoneNumbersIn("Room 12, 3rd floor, 14:30"))
    }

    @Test fun `links are found with their punctuation dropped and made openable`() {
        assertEquals(listOf("https://example.com/a?b=1", "www.osm.org/x"), urlsIn("See https://example.com/a?b=1. Or www.osm.org/x, later"))
        assertEquals("https://www.osm.org/x", openableUrl("www.osm.org/x"))
        assertEquals(emptyList(), urlsIn("mail me at a@b.example.com"))
    }

    @Test fun `a map search encodes the address`() {
        assertEquals(true, mapsSearchUri("1 Main St, Springfield").endsWith("1+Main+St%2C+Springfield") || mapsSearchUri("1 Main St, Springfield").endsWith("1%20Main%20St%2C%20Springfield"))
    }
}
