package io.nisfeb.talon.ai

import io.nisfeb.talon.data.WeatherToday
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * open-meteo.com weather fetcher. No API key needed. Returns null on
 * any failure — caller treats null as "skip the weather card."
 *
 * See spec §Weather + §Generation step 6.
 */
class WeatherClient(private val http: OkHttpClient) {

    /**
     * Fetch today's high/low + condition for [lat],[lon]. Returns null
     * on network error, non-2xx, parse failure, or empty data.
     */
    suspend fun fetchToday(lat: Double, lon: Double): WeatherToday? = runCatching {
        val url = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", lat.toString())
            .addQueryParameter("longitude", lon.toString())
            .addQueryParameter("daily", "temperature_2m_max,temperature_2m_min,weather_code")
            .addQueryParameter("forecast_days", "1")
            .addQueryParameter("timezone", "auto")
            .build()
        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            parseToday(resp.body?.string() ?: return@runCatching null)
        }
    }.getOrNull()

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        fun parseToday(body: String): WeatherToday? = runCatching {
            val obj = JSON.parseToJsonElement(body).jsonObject
            val daily = obj["daily"]?.jsonObject ?: return@runCatching null
            val high = daily["temperature_2m_max"]?.jsonArray?.firstOrNull()
                ?.jsonPrimitive?.doubleOrNull ?: return@runCatching null
            val low = daily["temperature_2m_min"]?.jsonArray?.firstOrNull()
                ?.jsonPrimitive?.doubleOrNull ?: return@runCatching null
            val code = daily["weather_code"]?.jsonArray?.firstOrNull()
                ?.jsonPrimitive?.intOrNull ?: return@runCatching null
            val (label, emoji) = wmoLookup(code)
            WeatherToday(highC = high, lowC = low, conditionCode = code,
                conditionLabel = label, emoji = emoji)
        }.getOrNull()

        // WMO weather code → (label, emoji). Subset based on
        // open-meteo's published code list. Missing codes fall back
        // to the catch-all "Unknown" / 🌡️.
        // https://open-meteo.com/en/docs#weathervariables
        private fun wmoLookup(code: Int): Pair<String, String> = when (code) {
            0 -> "Clear" to "☀️"
            1 -> "Mainly clear" to "🌤️"
            2 -> "Partly cloudy" to "⛅"
            3 -> "Overcast" to "☁️"
            45, 48 -> "Fog" to "🌫️"
            51, 53, 55 -> "Drizzle" to "🌦️"
            56, 57 -> "Freezing drizzle" to "🌧️"
            61, 63, 65 -> "Rain" to "🌧️"
            66, 67 -> "Freezing rain" to "🌧️"
            71, 73, 75, 77 -> "Snow" to "🌨️"
            80, 81, 82 -> "Rain showers" to "🌦️"
            85, 86 -> "Snow showers" to "🌨️"
            95 -> "Thunderstorm" to "⛈️"
            96, 99 -> "Thunderstorm w/ hail" to "⛈️"
            else -> "Unknown" to "🌡️"
        }
    }
}
