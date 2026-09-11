package io.nisfeb.talon.widget

import android.content.Context
import io.nisfeb.talon.ui.HomePlace
import io.nisfeb.talon.ui.HomePrefs
import io.nisfeb.talon.ui.HomePlaceCodec
import io.nisfeb.talon.ui.Moon
import io.nisfeb.talon.ui.SkyClock
import io.nisfeb.talon.ui.Solar
import io.nisfeb.talon.ui.parseForecast
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime

/**
 * What the home-screen widget needs to draw, read from where the app
 * already keeps it.
 *
 * The same preferences the app's own settings write, by the same keys.
 * A widget with its own copy of the place would be a second thing to
 * set and a second thing to get wrong.
 */
object WidgetSky {


    /** The widget's own corner, for the last forecast it managed to get. */
    private const val WIDGET_PREFS = "talon_widget"
    private const val KEY_FORECAST = "forecast_body"
    private const val KEY_FORECAST_AT = "forecast_at"
    private const val KEY_FORECAST_FOR = "forecast_for"

    data class Settings(
        val place: HomePlace?,
        val fahrenheit: Boolean,
        val twentyFourHour: Boolean,
    )

    fun settings(context: Context): Settings {
        // The app's own file and the app's own keys, by reference
        // rather than by retyping them.
        val p = context.getSharedPreferences(HomePrefs.FILE, Context.MODE_PRIVATE)
        return Settings(
            place = HomePlaceCodec.decode(p.getString(HomePrefs.PLACE, "") ?: ""),
            fahrenheit = p.getBoolean(HomePrefs.FAHRENHEIT, true),
            twentyFourHour = p.getBoolean(HomePrefs.TWENTY_FOUR_HOUR, false),
        )
    }

    /**
     * The forecast body last fetched, if it is still for this place and
     * still worth believing.
     *
     * Kept as the raw answer rather than as a parsed Sky: it is one
     * string to store, and parsing it again costs nothing next to
     * having fetched it.
     */
    fun cachedForecast(context: Context, place: HomePlace?, nowMs: Long): String? {
        if (place == null) return null
        val p = context.getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE)
        if (p.getString(KEY_FORECAST_FOR, null) != placeKey(place)) return null
        val at = p.getLong(KEY_FORECAST_AT, 0L)
        if (nowMs - at > FORECAST_GOOD_FOR_MS || nowMs < at) return null
        return p.getString(KEY_FORECAST, null)
    }

    /**
     * Whatever was last fetched, however old and whatever place it was
     * for. The last resort when a fetch fails: a dial carrying an
     * older temperature beats one that has dropped the temperature
     * because the phone was briefly on a train.
     */
    fun staleForecast(context: Context): String? =
        context.getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_FORECAST, null)

    fun rememberForecast(context: Context, place: HomePlace, body: String, nowMs: Long) {
        context.getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_FORECAST, body)
            .putString(KEY_FORECAST_FOR, placeKey(place))
            .putLong(KEY_FORECAST_AT, nowMs)
            .apply()
    }

    /** Coarse on purpose: a place that has moved a few hundred metres
     *  has not moved to a different forecast. */
    private fun placeKey(p: HomePlace): String =
        "${(p.lat * 100).toInt()},${(p.lon * 100).toInt()}"

    /** An hour. Longer than the app's half hour, because a widget that
     *  wakes less often should not throw away what it has. */
    const val FORECAST_GOOD_FOR_MS = 60 * 60_000L

    /**
     * The dial's state, from a place, a moment and whatever weather is
     * to hand. The same arithmetic the app's own home page does.
     */
    fun skyFor(atMs: Long, place: HomePlace?, forecastBody: String?): SkyClock.Sky {
        val weather = forecastBody?.let { parseForecast(it) }
        val zone = place?.timeZoneId?.let { runCatching { TimeZone.of(it) }.getOrNull() }
            ?: weather?.zoneId?.let { runCatching { TimeZone.of(it) }.getOrNull() }
            ?: TimeZone.currentSystemDefault()
        val instant = Instant.fromEpochMilliseconds(atMs)
        val local = instant.toLocalDateTime(zone)
        val minuteOfDay = local.hour * 60 + local.minute
        val offsetMinutes = zone.offsetAt(instant).totalSeconds / 60

        val sun = place?.let {
            Solar.sunTimes(
                latitude = it.lat,
                longitude = it.lon,
                dayOfYear = local.date.dayOfYear,
                zoneOffsetMinutes = offsetMinutes,
                elevationMetres = it.elevationMetres ?: 0.0,
            )
        }
        val base = weather ?: SkyClock.Sky(minuteOfDay = minuteOfDay)
        return base.copy(
            minuteOfDay = minuteOfDay,
            dateLabel = dayLabel(local.dayOfMonth, local.monthNumber),
            sunriseMinute = sun?.sunriseMinute ?: base.sunriseMinute,
            sunsetMinute = sun?.sunsetMinute ?: base.sunsetMinute,
            twilight = place?.let { Solar.twilightMinutes(it.lat) } ?: base.twilight,
            polar = sun?.polar ?: false,
            polarDay = sun?.polarDay ?: false,
            moonElongationDeg = Moon.phaseAt(atMs).elongationDeg,
        )
    }

    private val MONTHS = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )

    internal fun dayLabel(day: Int, month: Int): String {
        val suffix = when {
            day % 100 in 11..13 -> "th"
            day % 10 == 1 -> "st"
            day % 10 == 2 -> "nd"
            day % 10 == 3 -> "rd"
            else -> "th"
        }
        return "${MONTHS[(month - 1).coerceIn(0, 11)]} $day$suffix"
    }
}
