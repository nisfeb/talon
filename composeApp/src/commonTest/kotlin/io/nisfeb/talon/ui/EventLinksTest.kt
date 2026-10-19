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
        assertEquals(listOf("urb://~zod/chat/x"), urlsIn("see urb://~zod/chat/x"), "an urb address is a link too")
    }

    @Test fun `image links are the ones naming a picture`() {
        assertEquals(listOf("https://a.example/p.jpg?x=1", "https://b.example/q.PNG"), imageUrlsIn("https://a.example/p.jpg?x=1 https://b.example/q.PNG https://c.example/page urb://~zod/x.png"))
    }

    @Test fun `a map search encodes the address`() {
        assertEquals(true, mapsSearchUri("1 Main St, Springfield").endsWith("1+Main+St%2C+Springfield") || mapsSearchUri("1 Main St, Springfield").endsWith("1%20Main%20St%2C%20Springfield"))
    }

    // sneagan, 2026-10-19: directions laid out, one tap from starting.
    @Test
    fun `directions are each platform's own, by how the owner travels`() {
        assertEquals("https://www.google.com/maps/dir/?api=1&destination=Sagrada%20Fam%C3%ADlia&travelmode=walking", googleDirectionsUri("Sagrada%20Fam%C3%ADlia", "walking"))
        assertEquals("https://www.google.com/maps/dir/?api=1&destination=Hotel", googleDirectionsUri("Hotel", null))
        assertEquals("maps://?daddr=Hotel&dirflg=w", appleDirectionsUri("Hotel", "walking"))
        assertEquals("maps://?daddr=Hotel&dirflg=d", appleDirectionsUri("Hotel", "driving"))
        assertEquals("maps://?daddr=Hotel&dirflg=r", appleDirectionsUri("Hotel", "transit"))
        assertEquals("maps://?daddr=Hotel", appleDirectionsUri("Hotel", "bicycling"), "Apple has no flag for a bicycle")
        assertEquals(mapsDirectionsUriFor("Hotel", null), mapsDirectionsUri(" Hotel ", "by camel"), "a mode it does not know is left out")
    }
}
