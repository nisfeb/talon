package io.nisfeb.talon.ui

import io.nisfeb.talon.ui.screens.parseCoordinates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomePlaceTest {

    @Test
    fun `a place survives being stored and read back`() {
        // A stored place that comes back slightly different is a dial
        // that has quietly moved somewhere else.
        val p = HomePlace(42.3601, -71.0589, "Boston, Massachusetts, US", fromGps = false, elevationMetres = 43.0)
        assertEquals(p, HomePlaceCodec.decode(HomePlaceCodec.encode(p)))
    }

    @Test
    fun `a label with commas in it still round-trips`() {
        val p = HomePlace(51.5, -0.13, "London, England, United Kingdom")
        assertEquals(p, HomePlaceCodec.decode(HomePlaceCodec.encode(p)))
    }

    @Test
    fun `a device fix stays marked as one`() {
        val p = HomePlace(1.0, 2.0, "here", fromGps = true)
        assertTrue(HomePlaceCodec.decode(HomePlaceCodec.encode(p))!!.fromGps)
    }

    @Test
    fun `an unknown elevation stays unknown rather than becoming sea level`() {
        val p = HomePlace(1.0, 2.0, "here", elevationMetres = null)
        assertNull(HomePlaceCodec.decode(HomePlaceCodec.encode(p))!!.elevationMetres)
    }

    @Test
    fun `a time zone survives storage`() {
        // The field the dial's orientation hangs on.
        val p = HomePlace(-41.29, 174.78, "Wellington, New Zealand", timeZoneId = "Pacific/Auckland")
        assertEquals("Pacific/Auckland", HomePlaceCodec.decode(HomePlaceCodec.encode(p))?.timeZoneId)
        assertEquals(p, HomePlaceCodec.decode(HomePlaceCodec.encode(p)))
    }

    @Test
    fun `a place with no zone stays without one`() {
        val p = HomePlace(1.0, 2.0, "here", fromGps = true)
        assertNull(HomePlaceCodec.decode(HomePlaceCodec.encode(p))!!.timeZoneId)
    }

    @Test
    fun `a location saved before zones were carried still reads`() {
        // Dropping somebody's saved place to add a field would be a poor
        // trade. The old five-field line has no version tag.
        val old = "42.3601,-71.0589,0,43.0,Boston, Massachusetts, US"
        val p = HomePlaceCodec.decode(old)!!
        assertEquals(42.3601, p.lat)
        assertEquals("Boston, Massachusetts, US", p.label, "the label keeps its own commas")
        assertEquals(43.0, p.elevationMetres)
        assertNull(p.timeZoneId, "there was none to read")
    }

    @Test
    fun `nothing stored is no place`() {
        assertNull(HomePlaceCodec.decode(""))
        assertNull(HomePlaceCodec.decode("garbage"))
    }

    @Test
    fun `coordinates off the globe are refused`() {
        // A dial pointed at nowhere is worse than one that admits it has
        // no location.
        assertNull(HomePlaceCodec.decode("91.0,0.0,0,,North of north"))
        assertNull(HomePlaceCodec.decode("0.0,181.0,0,,Off the edge"))
    }

    @Test
    fun `typed coordinates are taken without a lookup`() {
        val p = parseCoordinates("42.36, -71.06")
        assertEquals(42.36, p?.lat)
        assertEquals(-71.06, p?.lon)
        assertEquals(false, p?.fromGps)
    }

    @Test
    fun `a typed place name is not mistaken for coordinates`() {
        assertNull(parseCoordinates("Boston"))
        assertNull(parseCoordinates("New York, NY"))
        assertNull(parseCoordinates("91, 0"), "off the globe is not a coordinate either")
    }
}
