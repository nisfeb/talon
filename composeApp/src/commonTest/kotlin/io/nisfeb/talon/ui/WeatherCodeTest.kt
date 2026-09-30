package io.nisfeb.talon.ui

import io.nisfeb.talon.ui.SkyClock.Weather
import kotlin.test.Test
import kotlin.test.assertEquals

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
