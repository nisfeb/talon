package io.nisfeb.talon.ui

import io.nisfeb.talon.ui.SkyClock.Weather
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WeatherCodeTest {

    @Test
    fun `the WMO bands land where they should`() {
        assertEquals(Weather.CLEAR, SkyClock.weatherOf(0))
        assertEquals(Weather.CLEAR, SkyClock.weatherOf(1))
        assertEquals(Weather.CLOUD, SkyClock.weatherOf(3))
        assertEquals(Weather.FOG, SkyClock.weatherOf(45))
        assertEquals(Weather.DRIZZLE, SkyClock.weatherOf(53))
        assertEquals(Weather.RAIN, SkyClock.weatherOf(65))
        assertEquals(Weather.RAIN, SkyClock.weatherOf(81), "showers are rain")
        assertEquals(Weather.SNOW, SkyClock.weatherOf(75))
        assertEquals(Weather.SNOW, SkyClock.weatherOf(86), "snow showers are snow")
        assertEquals(Weather.SLEET, SkyClock.weatherOf(66), "freezing rain is not rain")
        assertEquals(Weather.THUNDER, SkyClock.weatherOf(95))
    }

    @Test
    fun `an unknown code is clear rather than a guess`() {
        // A wrong icon is worse than no icon.
        assertEquals(Weather.CLEAR, SkyClock.weatherOf(null))
        assertEquals(Weather.CLEAR, SkyClock.weatherOf(7))
        assertEquals(Weather.CLEAR, SkyClock.weatherOf(-1))
        assertEquals(Weather.CLEAR, SkyClock.weatherOf(1000))
    }

    @Test
    fun `only falling weather counts as precipitation`() {
        assertFalse(Weather.CLEAR.precipitating)
        assertFalse(Weather.CLOUD.precipitating)
        assertFalse(Weather.FOG.precipitating, "fog sits, it does not fall")
        assertTrue(Weather.RAIN.precipitating)
        assertTrue(Weather.SNOW.precipitating)
    }

    @Test
    fun `rain darkens the sky and a clear one is left alone`() {
        assertEquals(0f, Weather.CLEAR.gloom)
        assertEquals(0f, Weather.CLOUD.gloom, "cloud drains colour elsewhere; it does not darken twice")
        assertTrue(Weather.RAIN.gloom > Weather.DRIZZLE.gloom)
        assertTrue(Weather.THUNDER.gloom > Weather.RAIN.gloom, "a storm is the darkest sky")
        assertTrue(Weather.SNOW.gloom < Weather.RAIN.gloom, "snow falls out of a brighter sky")
        for (w in Weather.entries) assertTrue(w.gloom in 0f..0.8f, "$w gloom ${w.gloom}")
    }

    @Test
    fun `the code comes off the wire into the dial`() {
        val s = parseForecast(
            """{"current":{"temperature_2m":3.0,"cloud_cover":90,"weather_code":73}}""",
        )!!
        assertEquals(Weather.SNOW, s.condition)
        assertEquals(0.9f, s.cloudCover)
    }

    @Test
    fun `an answer with no code still reads as weather`() {
        val s = parseForecast("""{"current":{"temperature_2m":3.0}}""")!!
        assertEquals(Weather.CLEAR, s.condition)
    }
}
