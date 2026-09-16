package io.nisfeb.talon.ui.screens

import io.nisfeb.talon.ui.SkyClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DialReadinessTest {

    private val hour = 60 * 60_000L

    @Test
    fun `nothing fetched yet is stale`() {
        assertTrue(weatherIsStale(0L, 1_789_128_000_000L))
    }

    @Test
    fun `a fresh answer is left alone`() {
        val now = 1_789_128_000_000L
        assertTrue(!weatherIsStale(now, now))
        assertTrue(!weatherIsStale(now - 5 * 60_000L, now), "five minutes old is fine")
    }

    @Test
    fun `a laptop that slept comes back to a refetch`() {
        // The bug this replaced: delay(30 min) suspends along with the
        // machine, so eight hours of shut lid resumed with whatever was
        // left on the clock and showed last night's weather.
        val now = 1_789_128_000_000L
        assertTrue(weatherIsStale(now - 8 * hour, now))
        assertTrue(weatherIsStale(now - WEATHER_MAX_AGE_MS, now), "exactly at the limit counts")
    }

    @Test
    fun `a clock that went backwards refetches rather than waiting`() {
        // A machine that woke and then corrected its time would
        // otherwise sit on an answer it believes is from the future.
        val now = 1_789_128_000_000L
        assertTrue(weatherIsStale(now + hour, now))
    }

    @Test
    fun `the dial says out loud what only the drawing shows`() {
        val sky = SkyClock.Sky(
            minuteOfDay = 13 * 60,
            sunriseMinute = 7 * 60 + 8,
            sunsetMinute = 19 * 60 + 38,
            currentC = 21.0,
            highC = 24.0, highAtMinute = 16 * 60,
            lowC = 13.0, lowAtMinute = 6 * 60,
            condition = SkyClock.Weather.RAIN,
        )
        val said = dialDescription(sky, fahrenheit = false, twentyFourHour = true)
        assertTrue(said.contains("sun is up"), said)
        assertTrue(said.contains("19:38"), "it should say when the sun goes: $said")
        assertTrue(said.contains("Rain"), said)
        assertTrue(said.contains("High 24"), said)
        assertTrue(said.contains("Low 13"), said)
        assertTrue(said.contains("12 hours and 30 minutes"), said)
    }

    @Test
    fun `it speaks in the units the dial is set to`() {
        val sky = SkyClock.Sky(minuteOfDay = 0, highC = 0.0, highAtMinute = 60)
        assertTrue(dialDescription(sky, fahrenheit = true, twentyFourHour = false).contains("32"))
        assertTrue(dialDescription(sky, fahrenheit = false, twentyFourHour = true).contains("High 0"))
    }

    @Test
    fun `a dial with no weather still has something to say`() {
        // Before a place is set there is no forecast at all, and silence
        // would be the one case a screen reader gets nothing.
        val said = dialDescription(SkyClock.Sky(minuteOfDay = 3 * 60), true, false)
        assertTrue(said.contains("sun is down"), said)
        assertTrue(said.isNotBlank())
    }

    @Test
    fun `the polar cases do not claim a sunrise`() {
        val night = SkyClock.Sky(minuteOfDay = 0, polar = true, polarDay = false)
        assertTrue(dialDescription(night, true, false).contains("does not rise"))
        val day = SkyClock.Sky(minuteOfDay = 0, polar = true, polarDay = true)
        assertTrue(dialDescription(day, true, false).contains("does not set"))
    }

    @Test
    fun `a clear sky is not announced as a condition`() {
        val sky = SkyClock.Sky(minuteOfDay = 12 * 60, condition = SkyClock.Weather.CLEAR)
        val said = dialDescription(sky, true, false)
        assertEquals(said, said.replace("Cloudy", "!"), "clear should add nothing: $said")
    }
}
