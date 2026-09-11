package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The forecast request is asked by two things now — the app and the
 * home-screen widget, from different processes — so it is one function
 * rather than two spellings of a URL.
 */
class ForecastRequestTest {

    private val exact = HomePlace(42.3600825, -71.0588801, "Boston")

    @Test
    fun `the position sent is rounded, not the one we hold`() {
        // About a kilometre, which is coarser than the forecast model's
        // own cells. Sending the fix itself would give away more than
        // the answer uses.
        val url = OpenMeteoWeather.requestUrl(exact)
        assertTrue(url.contains("latitude=42.36"), url)
        assertTrue(url.contains("longitude=-71.05"), url)
        assertTrue(!url.contains("42.3600825"), "the raw latitude went out: $url")
        assertTrue(!url.contains("71.0588801"), "the raw longitude went out: $url")
    }

    @Test
    fun `it asks for everything the parser reads`() {
        // A field dropped here comes back as a dial with no weather on
        // it, and nothing anywhere says why.
        val url = OpenMeteoWeather.requestUrl(exact)
        for (field in listOf("temperature_2m", "cloud_cover", "weather_code")) {
            assertTrue(url.contains("current=") && url.contains(field), "no $field: $url")
        }
        assertTrue(url.contains("hourly=temperature_2m,cloud_cover,weather_code"), url)
        assertTrue(url.contains("timezone=auto"), "without this the dial runs on the wrong clock")
    }

    @Test
    fun `the southern and western hemispheres survive rounding`() {
        val wellington = OpenMeteoWeather.requestUrl(HomePlace(-41.2923, 174.7787, "Wellington"))
        assertTrue(wellington.contains("latitude=-41.29"), wellington)
        assertTrue(wellington.contains("longitude=174.77"), wellington)
    }

    @Test
    fun `a place on the line does not come out as minus nothing`() {
        val url = OpenMeteoWeather.requestUrl(HomePlace(0.0, 0.0, "Null Island"))
        assertTrue(url.contains("latitude=0.0"), url)
        assertTrue(!url.contains("-0.0"), "a negative zero went out: $url")
    }
}
