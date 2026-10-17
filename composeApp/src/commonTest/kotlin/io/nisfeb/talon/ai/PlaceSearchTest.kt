package io.nisfeb.talon.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading a place search and saying the nearest (sneagan, 2026-10-17:
 * "what is the closest home Depot to nutcracker rehearsal"). The fields
 * are Brave's, as Orrery's place lookups read them: coordinates are
 * [lat, lon], the address is displayAddress or its parts, today's hours
 * are opening_hours.current_day.
 */
class PlaceSearchTest {
    private val theatre = FoundPlace("Florida Theatre", "128 E Forsyth St, Jacksonville, FL 32202", 30.3290, -81.6568, "", "")

    private val found = """{"results":[
        {"type":"location_result","title":"The Home Depot","coordinates":[30.2600,-81.6000],
         "postal_address":{"streetAddress":"9 Far Rd","addressLocality":"Jacksonville","addressRegion":"FL","postalCode":"32256"},
         "contact":{"telephone":"(904) 555-0101"},
         "opening_hours":{"current_day":[{"abbr_name":"Fri","full_name":"Friday","opens":"06:00","closes":"22:00"}]}},
        {"type":"location_result","title":"The Home Depot","coordinates":[30.3150,-81.6700],
         "postal_address":{"displayAddress":"1 Near St, Jacksonville, FL 32204"}},
        {"type":"location_result","title":"Nowhere Hardware","postal_address":{"displayAddress":"somewhere"}}
    ]}"""

    @Test
    fun `a result is read for its place, and one with no position is left out`() {
        val places = parsePlaces(found)
        assertEquals(2, places.size, "no coordinates, no distance")
        assertEquals(FoundPlace("The Home Depot", "9 Far Rd, Jacksonville, FL, 32256", 30.26, -81.6, "(904) 555-0101", "06:00-22:00"), places[0])
        assertEquals("1 Near St, Jacksonville, FL 32204", places[1].address)
        assertEquals(emptyList(), parsePlaces("not json"))
        assertEquals(emptyList(), parsePlaces("""{"results":"no"}"""))
    }

    // The search does not say in what order it answers: the nearer of
    // two comes first whatever its place in the list.
    @Test
    fun `the nearest comes first, from the place it was found as, and only as many as asked`() {
        val said = placesAnswer("Home Depot", theatre, parsePlaces(found), 3)
        val lines = said.lines()
        assertEquals("Nearest \"Home Depot\" to Florida Theatre, 128 E Forsyth St, Jacksonville, FL 32202, straight-line:", lines[0])
        assertTrue(lines[1] == "1. The Home Depot, 1.2 mi (2 km): 1 Near St, Jacksonville, FL 32204", lines[1])
        assertEquals("2. The Home Depot, 5.8 mi (9.4 km): 9 Far Rd, Jacksonville, FL, 32256. Open today 06:00-22:00. (904) 555-0101", lines[2])
        assertEquals(2, placesAnswer("Home Depot", theatre, parsePlaces(found), 1).lines().size)
        assertEquals("No \"hardware store\" found near Florida Theatre, 128 E Forsyth St, Jacksonville, FL 32202.", placesAnswer("hardware store", theatre, emptyList(), 3))
    }

    @Test
    fun `distances are straight-line, and said in miles and kilometres`() {
        assertEquals(111_195.0, distanceM(0.0, 0.0, 0.0, 1.0), 1.0)
        assertEquals(0.0, distanceM(30.0, -81.0, 30.0, -81.0), 0.001)
        assertEquals("1 mi (1.6 km)", milesKm(1609.344))
        assertEquals("2.3 mi (3.7 km)", milesKm(3700.0))
        assertEquals("0 mi (0 km)", milesKm(10.0))
    }

    @Test
    fun `near is a position only when it reads as one`() {
        assertEquals(30.329 to -81.6568, latLonOf("30.329, -81.6568"))
        assertNull(latLonOf("128 E Forsyth St, Jacksonville"))
        assertNull(latLonOf("91, 200"), "longitude out of range")
        assertNull(latLonOf("30.3"))
    }
}
