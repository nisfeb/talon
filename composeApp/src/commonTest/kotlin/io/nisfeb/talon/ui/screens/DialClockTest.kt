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
