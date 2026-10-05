package io.nisfeb.talon.orrery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The position's wire, against orrery's own reader: +coordinate parses
 * `;~(plug (punt hep) dem (punt ;~(pfix dot (plus nud))))`, so a number
 * is an optional minus, digits, and optional decimals. No exponent, no
 * plus sign.
 */
class PositionBodyTest {
    private val shipNumber = Regex("^-?\\d+(\\.\\d+)?$")

    private fun numbers(body: String) =
        Regex("\"(lat|lon|acc)\":([^,}]+)").findAll(body).associate { it.groupValues[1] to it.groupValues[2] }

    @Test
    fun `the body is the ship's shape, numbers as JSON numbers`() {
        val body = positionBody(LocationFix(30.2012, -81.6034, 12.4, 1_791_115_200_000L))
        assertEquals("""{"lat":30.201200,"lon":-81.603400,"acc":12,"at":"2026-10-04T12:00:00Z"}""", body)
    }

    @Test
    fun `numbers near zero have no exponent, and no minus zero`() {
        // A Double near Greenwich prints as 4.0E-5 on its own.
        val body = positionBody(LocationFix(51.4779, 0.00004, 3.0, 1_791_115_200_000L))
        assertEquals("0.000040", numbers(body)["lon"])
        assertEquals("0.000000", fixed(-0.0000001, 6), "rounds to zero without a sign")
        assertEquals("-0.000100", fixed(-0.0001, 6))
        for ((k, v) in numbers(body)) assertTrue(shipNumber.matches(v), "$k=$v is not a number the ship reads")
    }

    @Test
    fun `every corner of the earth reads as the ship's number`() {
        for ((lat, lon) in listOf(90.0 to 180.0, -90.0 to -180.0, -33.8688 to 151.2093, 0.0 to 0.0, 1e-9 to -1e-9)) {
            val n = numbers(positionBody(LocationFix(lat, lon, 0.0, 0L)))
            for ((k, v) in n) assertTrue(shipNumber.matches(v), "$k=$v from ($lat, $lon)")
        }
    }
}
