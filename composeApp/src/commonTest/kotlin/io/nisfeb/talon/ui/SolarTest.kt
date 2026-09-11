package io.nisfeb.talon.ui

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The dial's dark half is not a fixed twelve hours. These pin the
 * cases a six-to-six dial gets confidently wrong.
 */
class SolarTest {

    // Day numbers for the solstices, near enough.
    private val june = 172
    private val december = 355

    @Test
    fun `the equator keeps its halves near even all year`() {
        for (day in listOf(june, december, 1, 90)) {
            val t = Solar.sunTimes(latitude = 0.0, longitude = 0.0, dayOfYear = day, zoneOffsetMinutes = 0)
            assertFalse(t.polar)
            val daylight = Solar.daylightMinutes(t)
            assertTrue(
                abs(daylight - 12 * 60) < 20,
                "day $day gave ${daylight / 60.0}h of daylight on the equator",
            )
        }
    }

    @Test
    fun `a northern summer is long and a northern winter is short`() {
        val lat = 51.5 // London
        val summer = Solar.daylightMinutes(Solar.sunTimes(lat, 0.0, june, 0))
        val winter = Solar.daylightMinutes(Solar.sunTimes(lat, 0.0, december, 0))
        assertTrue(summer > 15 * 60, "midsummer gave ${summer / 60.0}h")
        assertTrue(winter < 9 * 60, "midwinter gave ${winter / 60.0}h")
    }

    @Test
    fun `the southern hemisphere has it the other way round`() {
        val lat = -33.9 // Sydney
        val june = Solar.daylightMinutes(Solar.sunTimes(lat, 0.0, june, 0))
        val december = Solar.daylightMinutes(Solar.sunTimes(lat, 0.0, december, 0))
        assertTrue(december > june, "December should be the long one south of the equator")
    }

    @Test
    fun `past the arctic circle the sun does not set in summer`() {
        val t = Solar.sunTimes(latitude = 78.0, longitude = 15.0, dayOfYear = june, zoneOffsetMinutes = 60)
        assertTrue(t.polar)
        assertTrue(t.polarDay, "midsummer at 78 north is a day that does not end")
        assertEquals(SkyClock.MINUTES_IN_DAY, Solar.daylightMinutes(t))
    }

    @Test
    fun `past the arctic circle the sun does not rise in winter`() {
        val t = Solar.sunTimes(latitude = 78.0, longitude = 15.0, dayOfYear = december, zoneOffsetMinutes = 60)
        assertTrue(t.polar)
        assertFalse(t.polarDay, "midwinter at 78 north is a night that does not end")
        assertEquals(0, Solar.daylightMinutes(t))
    }

    @Test
    fun `sunrise lands at a believable hour`() {
        // London in late June: sunrise is just before 5am British Summer
        // Time, sunset just after 9pm.
        val t = Solar.sunTimes(51.5, -0.13, june, zoneOffsetMinutes = 60)
        assertTrue(t.sunriseMinute in (4 * 60)..(5 * 60), SkyClock.clockLabel(t.sunriseMinute, true))
        assertTrue(t.sunsetMinute in (20 * 60 + 45)..(21 * 60 + 45), SkyClock.clockLabel(t.sunsetMinute, true))
    }

    @Test
    fun `twilight lengthens away from the equator`() {
        assertTrue(Solar.twilightMinutes(0.0) < Solar.twilightMinutes(60.0))
        // Bounded, because the dial wants a band that looks right rather
        // than a number anybody depends on.
        assertTrue(Solar.twilightMinutes(89.9) <= 180)
    }
}
