package io.nisfeb.talon.orrery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a fix says, without a ship to say it to: which of the ship's
 * places it is at, else the name the phone looked up, else that the
 * owner has left. Pure, so it runs on every target rather than on the
 * desktop alone, where the sending half has to live for its database.
 */
class LocationValueTest {
    private val state = Json.parseToJsonElement(
        """{"me": "person/me", "bodies": [
            {"id": "place/home", "name": "Home", "attrs": {"geo": {"value": "40.6782, -73.9442"}}},
            {"id": "place/office", "name": "Office", "attrs": {"geo": {"value": {"lat": 40.7536, "lng": -73.9832}}}},
            {"id": "place/nowhere", "name": "Nowhere", "attrs": {}},
            {"id": "person/rose", "name": "Rose", "attrs": {"geo": {"value": "1,1"}}}]}""",
    ).jsonObject

    private fun fix(lat: Double, lon: Double, acc: Double = 20.0) = LocationFix(lat, lon, acc, 1_789_000_000_000L)

    @Test
    fun `places with a position the ship has, in either shape`() {
        assertEquals(listOf("place/home", "place/office"), geoPlaces(state).map { it.id })
        val d = metresBetween(40.6782, -73.9442, 40.7536, -73.9832)
        assertTrue(d in 9_000.0..9_300.0, "Brooklyn to midtown is about 9 km, not $d")
    }

    @Test
    fun `a known place, else a name, else leaving is said once`() {
        val places = geoPlaces(state)
        assertEquals(buildJsonObject { put("ref", "place/home") }, locationValue(fix(40.6790, -73.9442), places, "Crown Heights, New York", null))
        assertEquals(JsonPrimitive("Crown Heights, New York"), locationValue(fix(40.6700, -73.9442), places, "Crown Heights, New York", null), "900 m off is not home")
        assertEquals(buildJsonObject { put("ref", "place/home") }, locationValue(fix(40.6810, -73.9442, acc = 400.0), places, null, null), "a loose fix reaches as far as it is loose")
        assertEquals(JsonNull, locationValue(fix(10.0, 10.0), places, null, buildJsonObject { put("ref", "place/home") }), "left, and nowhere known")
        assertNull(locationValue(fix(10.0, 10.0), places, null, JsonNull), "said already")
        assertNull(locationValue(fix(10.0, 10.0), places, null, null))
    }
}
