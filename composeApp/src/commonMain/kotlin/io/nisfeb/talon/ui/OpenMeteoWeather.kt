package io.nisfeb.talon.ui

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Turning a place into today's weather.
 *
 * Null where nothing is wired, which keeps the dial honest: it draws
 * the day and says nothing about the temperature rather than drawing
 * a temperate blue it has no reason to believe.
 */
typealias WeatherLookup = suspend (HomePlace) -> Result<SkyClock.Sky>

/**
 * Today's temperature and cloud, from Open-Meteo.
 *
 * THIS SENDS COORDINATES TO A THIRD PARTY, and unlike the geocoder it
 * does so on its own once a place is set, because that is what a
 * weather dial is. Coarse by nature — the request carries a rounded
 * position, which is all the forecast grid resolves to anyway, so the
 * number that leaves is a neighbourhood rather than an address.
 *
 * No key and no account.
 */
class OpenMeteoWeather(private val http: HttpClient) {

    suspend fun fetch(place: HomePlace): Result<SkyClock.Sky> = runCatching {
        // Two decimal places is about a kilometre, and the forecast
        // model's own cells are coarser than that. Sending the raw fix
        // would give away more than the answer uses.
        val lat = round2(place.lat)
        val lon = round2(place.lon)
        val url = "$ENDPOINT?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,cloud_cover,weather_code" +
            "&hourly=temperature_2m&forecast_days=1&timezone=auto"
        val resp = http.get(url)
        if (!resp.status.isSuccess()) error("HTTP ${resp.status.value}")
        parseForecast(resp.bodyAsText()) ?: error("no weather in the answer")
    }

    fun asLookup(): WeatherLookup = { p -> fetch(p) }

    private companion object {
        const val ENDPOINT = "https://api.open-meteo.com/v1/forecast"
    }
}

private fun round2(v: Double): Double = (v * 100).toLong() / 100.0

/**
 * The weather half of a [SkyClock.Sky] — temperature, the day's high
 * and low with the hour each falls on, and cloud. The sun's times and
 * the current minute are the caller's business and are left at their
 * defaults here.
 *
 * Pure so the awkward answers can be tested: a day with one hour of
 * forecast in it, a missing cloud figure, a flat temperature where
 * the high and the low are the same hour.
 */
internal fun parseForecast(body: String): SkyClock.Sky? {
    val f = runCatching { forecastJson.decodeFromString<Forecast>(body) }.getOrNull() ?: return null
    val current = f.current?.temperature ?: return null

    val times = f.hourly?.time.orEmpty()
    val temps = f.hourly?.temperature.orEmpty()
    // A short or ragged array is the service having less to say, not a
    // reason to drop the current temperature on the floor.
    val pairs = times.zip(temps).mapNotNull { (t, c) ->
        val m = minuteOfDay(t) ?: return@mapNotNull null
        if (c == null) null else m to c
    }
    val high = pairs.maxByOrNull { it.second }
    val low = pairs.minByOrNull { it.second }

    return SkyClock.Sky(
        minuteOfDay = 0,
        currentC = current,
        highC = high?.second,
        highAtMinute = high?.first,
        lowC = low?.second,
        lowAtMinute = low?.first,
        cloudCover = f.current.cloudCover?.let { (it / 100f).coerceIn(0f, 1f) },
        condition = SkyClock.weatherOf(f.current.weatherCode),
        // The request asks for the place's own zone, and the answer
        // says which one that turned out to be. It is how a set of
        // typed coordinates ever learns what clock it is on.
        zoneId = f.timezone?.takeIf { it.isNotBlank() },
    )
}

/** `2026-09-11T14:00` — the local wall clock, because the request asks
 *  for the place's own zone. Anything else is not a time we can plot. */
private fun minuteOfDay(stamp: String): Int? {
    val t = stamp.substringAfter('T', "")
    val h = t.substringBefore(':', "").toIntOrNull() ?: return null
    val m = t.substringAfter(':', "").take(2).toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}

private val forecastJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class Forecast(
    val timezone: String? = null,
    val current: Current? = null,
    val hourly: Hourly? = null,
)

@Serializable
private data class Current(
    @SerialName("temperature_2m") val temperature: Double? = null,
    @SerialName("cloud_cover") val cloudCover: Float? = null,
    @SerialName("weather_code") val weatherCode: Int? = null,
)

@Serializable
private data class Hourly(
    val time: List<String> = emptyList(),
    @SerialName("temperature_2m") val temperature: List<Double?> = emptyList(),
)
