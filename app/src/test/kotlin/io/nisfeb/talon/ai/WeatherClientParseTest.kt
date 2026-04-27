package io.nisfeb.talon.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherClientParseTest {

    private val SAMPLE = """
        {
          "latitude": 40.71,
          "longitude": -74.01,
          "daily": {
            "time": ["2026-04-26"],
            "temperature_2m_max": [18.3],
            "temperature_2m_min": [9.4],
            "weather_code": [3]
          }
        }
    """.trimIndent()

    @Test fun `parses open-meteo daily payload`() {
        val w = WeatherClient.parseToday(SAMPLE)!!
        assertEquals(18.3, w.highF, 0.01)
        assertEquals(9.4, w.lowF, 0.01)
        assertEquals(3, w.conditionCode)
        // WMO 3 = "Overcast" per open-meteo docs.
        assertEquals("Overcast", w.conditionLabel)
        assertEquals("☁️", w.emoji)
    }

    @Test fun `parses clear sky code 0`() {
        val payload = SAMPLE.replace("\"weather_code\": [3]", "\"weather_code\": [0]")
        val w = WeatherClient.parseToday(payload)!!
        assertEquals("Clear", w.conditionLabel)
        assertEquals("☀️", w.emoji)
    }

    @Test fun `unknown weather code falls back to neutral label`() {
        val payload = SAMPLE.replace("\"weather_code\": [3]", "\"weather_code\": [9999]")
        val w = WeatherClient.parseToday(payload)!!
        assertEquals(9999, w.conditionCode)
        assertEquals("Unknown", w.conditionLabel)
        assertEquals("🌡️", w.emoji)
    }

    @Test fun `missing daily block returns null`() {
        val payload = """{"latitude":40.71,"longitude":-74.01}"""
        assertNull(WeatherClient.parseToday(payload))
    }

    @Test fun `empty arrays return null`() {
        val payload = """
            {
              "daily": {
                "time": [],
                "temperature_2m_max": [],
                "temperature_2m_min": [],
                "weather_code": []
              }
            }
        """.trimIndent()
        assertNull(WeatherClient.parseToday(payload))
    }

    @Test fun `garbage input returns null`() {
        assertNull(WeatherClient.parseToday("not json"))
        assertNull(WeatherClient.parseToday(""))
    }
}
