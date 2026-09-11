package io.nisfeb.talon.ui.screens

import io.nisfeb.talon.ui.HomePlace
import io.nisfeb.talon.ui.SkyClock
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The dial has to run on the clock of the place it is showing.
 *
 * Reading a remote place's coordinates while shifting its sun times by
 * the device's own offset rotates the whole lit arc, fifteen degrees
 * per hour of error. It put New Zealand's daylight across the bottom
 * of the ring.
 */
class DialClockTest {

    // 2026-09-11T12:00:00Z
    private val noonUtc = 1_789_128_000_000L

    private val wellington = HomePlace(
        lat = -41.29, lon = 174.78, label = "Wellington",
        timeZoneId = "Pacific/Auckland",
    )
    private val jacksonville = HomePlace(
        lat = 30.33, lon = -81.65, label = "Jacksonville",
        timeZoneId = "America/New_York",
    )

    private fun hours(m: Int) = m / 60.0

    @Test
    fun `New Zealand's day is the right way up`() {
        val sky = skyFor(noonUtc, wellington, null)
        assertTrue(
            hours(sky.sunriseMinute) in 5.0..8.0,
            "sunrise at ${SkyClock.clockLabel(sky.sunriseMinute, true)}",
        )
        assertTrue(
            hours(sky.sunsetMinute) in 17.0..20.0,
            "sunset at ${SkyClock.clockLabel(sky.sunsetMinute, true)}",
        )
        assertTrue(sky.sunsetMinute > sky.sunriseMinute, "the day must not wrap midnight here")
    }

    @Test
    fun `the sun is up at local midday and down at local midnight`() {
        // The property that actually failed: the arc was the right
        // length and in the wrong half of the ring.
        for (place in listOf(wellington, jacksonville)) {
            val sky = skyFor(noonUtc, place, null)
            assertTrue(
                SkyClock.markIsSun(12 * 60, sky.sunriseMinute, sky.sunsetMinute),
                "${place.label}: the sun is down at noon",
            )
            assertTrue(
                !SkyClock.markIsSun(0, sky.sunriseMinute, sky.sunsetMinute),
                "${place.label}: the sun is up at midnight",
            )
        }
    }

    @Test
    fun `both hemispheres get the same length of day near the equinox`() {
        // September 11 is close enough to the equinox that Wellington
        // and Jacksonville should be within an hour of each other. The
        // latitude handling was never the problem.
        val nz = skyFor(noonUtc, wellington, null).daylightMinutes
        val us = skyFor(noonUtc, jacksonville, null).daylightMinutes
        assertTrue(
            kotlin.math.abs(nz - us) < 90,
            "daylight differs by ${kotlin.math.abs(nz - us)} minutes: $nz vs $us",
        )
    }

    @Test
    fun `the place's zone wins, then the forecast's, then the device's`() {
        assertEquals(TimeZone.of("Pacific/Auckland"), zoneFor("Pacific/Auckland"))
        assertEquals(TimeZone.currentSystemDefault(), zoneFor(null))
        // A stored place from before zones were carried, or a typed pair
        // of coordinates, leans on whatever the forecast reported.
        val typed = HomePlace(-41.29, 174.78, "-41.29, 174.78")
        val forecastSaidNz = SkyClock.Sky(minuteOfDay = 0, zoneId = "Pacific/Auckland")
        val sky = skyFor(noonUtc, typed, forecastSaidNz)
        assertTrue(hours(sky.sunriseMinute) in 5.0..8.0, "sunrise at ${sky.sunriseMinute}")
    }

    @Test
    fun `a zone id nobody recognises does not take the dial down with it`() {
        assertEquals(TimeZone.currentSystemDefault(), zoneFor("Nowhere/Atlantis"))
        assertEquals(TimeZone.currentSystemDefault(), zoneFor(""))
    }
}
