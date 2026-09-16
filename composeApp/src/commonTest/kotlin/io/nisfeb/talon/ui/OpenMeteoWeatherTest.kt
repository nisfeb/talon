package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenMeteoWeatherTest {

    private val day = """
    {"latitude":42.36,"longitude":-71.06,
     "current":{"time":"2026-09-11T14:00","temperature_2m":21.4,"cloud_cover":40},
     "hourly":{"time":["2026-09-11T00:00","2026-09-11T05:00","2026-09-11T15:00","2026-09-11T23:00"],
               "temperature_2m":[13.0,9.5,24.2,15.1]}}
    """

    @Test
    fun `the current temperature and cloud come through`() {
        val s = parseForecast(day)!!
        assertEquals(21.4, s.currentC)
        // Percent in, fraction out: the dial's overcast runs 0..1 and
        // handing it 40 would peg every cloudy hour at flat grey.
        assertEquals(0.4f, s.cloudCover)
    }

    @Test
    fun `the high and low are found with the hour each falls on`() {
        val s = parseForecast(day)!!
        assertEquals(24.2, s.highC)
        assertEquals(15 * 60, s.highAtMinute)
        assertEquals(9.5, s.lowC)
        assertEquals(5 * 60, s.lowAtMinute)
    }

    @Test
    fun `a flat day puts one mark on top of the other, and the dial hides it`() {
        val flat = """
        {"current":{"temperature_2m":10.0},
         "hourly":{"time":["2026-09-11T09:00","2026-09-11T09:00"],"temperature_2m":[10.0,10.0]}}
        """
        val s = parseForecast(flat)!!
        assertEquals(s.highAtMinute, s.lowAtMinute)
        assertTrue(!s.marksDistinct, "two ticks at the same minute would draw as one thick one")
    }

    @Test
    fun `a gap in the hourly run does not take the rest of the day with it`() {
        val ragged = """
        {"current":{"temperature_2m":12.0},
         "hourly":{"time":["2026-09-11T06:00","2026-09-11T07:00","2026-09-11T18:00"],
                   "temperature_2m":[5.0,null,20.0]}}
        """
        val s = parseForecast(ragged)!!
        assertEquals(20.0, s.highC)
        assertEquals(5.0, s.lowC)
    }

    @Test
    fun `no hourly run still leaves the current temperature on the dial`() {
        val s = parseForecast("""{"current":{"temperature_2m":7.0}}""")!!
        assertEquals(7.0, s.currentC)
        assertNull(s.highC)
        assertNull(s.highAtMinute)
    }

    @Test
    fun `no current temperature is no weather at all`() {
        // Better a dial that shows only the day than one that invents a
        // temperate blue it has no reason to believe.
        assertNull(parseForecast("""{"hourly":{"time":[],"temperature_2m":[]}}"""))
        assertNull(parseForecast("not json"))
        assertNull(parseForecast(""))
    }

    @Test
    fun `cloud cover is clamped to the range the dial draws`() {
        val s = parseForecast(
            """{"current":{"temperature_2m":1.0,"cloud_cover":140}}""",
        )!!
        assertEquals(1f, s.cloudCover)
    }
}
