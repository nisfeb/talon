package io.nisfeb.talon.ui

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The day, as a ring.
 *
 * Noon sits at the top and midnight at the bottom, so the sun's mark
 * travels clockwise the way it crosses the sky and the dark half is
 * literally the bottom of the dial. Everything here is pure: the
 * drawing reads these numbers and colours and does no arithmetic of
 * its own, so the rules can be tuned and argued about in one place.
 */
object SkyClock {

    const val MINUTES_IN_DAY = 24 * 60

    /**
     * How far from the sun the moon has to be before the dial draws
     * it, in degrees of elongation. Tuning knob: raise it to keep the
     * two markers further apart, lower it to trust the sky more.
     */
    const val MOON_MIN_ELONGATION_DEG = 15.0

    /**
     * Degrees clockwise from the top of the dial for a time of day.
     *
     * Noon is 0 and midnight is 180, so a time before noon sits on the
     * left of the dial and after noon on the right.
     */
    fun angleOf(minuteOfDay: Int): Float {
        val m = ((minuteOfDay % MINUTES_IN_DAY) + MINUTES_IN_DAY) % MINUTES_IN_DAY
        val fromNoon = m - 12 * 60
        return (fromNoon / MINUTES_IN_DAY.toFloat()) * 360f
    }

    /**
     * How lit the sky is at a minute of the day: -1 is deep night, 0
     * is the sun exactly on the horizon — which is when the sky is at
     * its most coloured — and +1 is full day.
     *
     * Continuous on purpose. Four flat bands drew the day as four
     * facts, and the sky does not do that; it slides. A dial somebody
     * leaves open all day should slide with it, so that catching it at
     * ten past five looks different from catching it at half four.
     *
     * Night settles more slowly than day breaks, which is why the two
     * ramps are not the same length: the sky keeps a little colour
     * well after the sun has gone.
     */
    fun skyMix(
        minuteOfDay: Int,
        sunriseMinute: Int,
        sunsetMinute: Int,
        twilightMinutes: Int = 60,
        polar: Boolean = false,
        polarDay: Boolean = false,
    ): Float {
        if (polar) return if (polarDay) 1f else -1f
        val half = (twilightMinutes / 2).coerceAtLeast(1).toFloat()
        val sinceRise = forward(sunriseMinute, minuteOfDay)
        val dayLength = forward(sunriseMinute, sunsetMinute)
        if (dayLength == 0) return -1f
        return if (sinceRise <= dayLength) {
            val d = minOf(sinceRise, dayLength - sinceRise)
            (d / half).coerceIn(0f, 1f)
        } else {
            val nightLength = MINUTES_IN_DAY - dayLength
            val sinceSet = forward(sunsetMinute, minuteOfDay)
            val d = minOf(sinceSet, nightLength - sinceSet)
            -((d / (half * 2f)).coerceIn(0f, 1f))
        }
    }

    private fun wrap(m: Int): Int = ((m % MINUTES_IN_DAY) + MINUTES_IN_DAY) % MINUTES_IN_DAY

    /** Minutes going forward from [from] to [to], over midnight if need be. */
    private fun forward(from: Int, to: Int): Int {
        val d = wrap(to) - wrap(from)
        return if (d < 0) d + MINUTES_IN_DAY else d
    }

    /**
     * How warm to render, from -1 (cold, blue) through 0 (temperate) to
     * +1 (hot, red).
     *
     * Anchored on what a person feels rather than on a scale's zero:
     * freezing is fully cold and blood heat is fully hot, and the
     * middle is the range nobody remarks on. Celsius in, because the
     * anchors are physical; the display converts separately.
     */
    fun warmth(celsius: Double?): Float {
        if (celsius == null) return 0f
        val cold = 0.0
        val hot = 35.0
        val mid = (cold + hot) / 2
        val span = (hot - cold) / 2
        return ((celsius - mid) / span).coerceIn(-1.0, 1.0).toFloat()
    }

    /**
     * What the sky is actually doing, as far as the dial cares.
     *
     * Coarser than the forecast's own vocabulary on purpose: the dial
     * has one small icon and a ring to say it with, and the difference
     * between slight and moderate drizzle is not something either can
     * carry. [gloom] is how much the light goes out of the day — rain
     * darkens a sky, snow rather less, and a thunderstorm most of all.
     */
    enum class Weather(val gloom: Float, val precipitating: Boolean) {
        CLEAR(0f, false),
        CLOUD(0f, false),
        FOG(0.26f, false),
        DRIZZLE(0.20f, true),
        RAIN(0.38f, true),
        SLEET(0.36f, true),
        // Snow falls out of a bright sky more often than a black one,
        // and the ground throws light back up into it.
        SNOW(0.20f, true),
        THUNDER(0.55f, true),
    }

    /**
     * A WMO present-weather code as something the dial can draw.
     *
     * The codes run in bands — sixties are rain, seventies snow,
     * eighties showers — and anything unrecognised is treated as clear
     * rather than guessed at, because a wrong icon is worse than none.
     */
    fun weatherOf(code: Int?): Weather = when (code) {
        null, 0, 1 -> Weather.CLEAR
        2, 3 -> Weather.CLOUD
        45, 48 -> Weather.FOG
        51, 53, 55 -> Weather.DRIZZLE
        56, 57, 66, 67 -> Weather.SLEET
        61, 63, 65, 80, 81, 82 -> Weather.RAIN
        71, 73, 75, 77, 85, 86 -> Weather.SNOW
        95, 96, 99 -> Weather.THUNDER
        else -> Weather.CLEAR
    }

    /**
     * How much the sky is doing something, from 0 (clear) to 1
     * (overcast). Drains the day band's colour rather than painting
     * grey over it, so a cloudy afternoon reads as flat rather than as
     * a different time of day.
     */
    fun overcast(cloudCover: Float?): Float = (cloudCover ?: 0f).coerceIn(0f, 1f)

    /**
     * Whether a body at [minuteOfDay] on the dial is above the horizon.
     *
     * Wrap-safe, which the plain range check was not: a sunset that
     * falls after local midnight — far north in summer, or a zone far
     * from its own meridian — leaves sunset before sunrise, and then
     * `rise until set` is empty and the sun is never up at all.
     */
    fun isUpAt(
        minuteOfDay: Int,
        sunriseMinute: Int,
        sunsetMinute: Int,
        polar: Boolean = false,
        polarDay: Boolean = false,
    ): Boolean {
        if (polar) return polarDay
        val dayLength = forward(sunriseMinute, sunsetMinute)
        if (dayLength == 0) return false
        return forward(sunriseMinute, minuteOfDay) < dayLength
    }

    /** Where the sun's mark sits. */
    fun markIsSun(minuteOfDay: Int, sunriseMinute: Int, sunsetMinute: Int): Boolean =
        isUpAt(minuteOfDay, sunriseMinute, sunsetMinute)

    /**
     * A time of day as a label. Deliberately not seconds: the ring is
     * a day, and a ticking seconds field on it invites watching.
     */
    fun clockLabel(minuteOfDay: Int, twentyFourHour: Boolean): String {
        val m = ((minuteOfDay % MINUTES_IN_DAY) + MINUTES_IN_DAY) % MINUTES_IN_DAY
        val h = m / 60
        val min = m % 60
        val mm = if (min < 10) "0$min" else "$min"
        if (twentyFourHour) return "${if (h < 10) "0$h" else "$h"}:$mm"
        val suffix = if (h < 12) "AM" else "PM"
        val h12 = when (h % 12) {
            0 -> 12
            else -> h % 12
        }
        return "$h12:$mm $suffix"
    }

    /** A temperature as it is spoken, rounded, with the degree sign. */
    fun tempLabel(celsius: Double?, fahrenheit: Boolean): String {
        if (celsius == null) return "—"
        val v = if (fahrenheit) celsius * 9 / 5 + 32 else celsius
        return "${v.roundToInt()}°"
    }

    /**
     * Everything the dial needs, with no opinion about where it came
     * from. A null temperature is a dial that shows the day and says
     * nothing about the weather, which is the honest state before a
     * source exists.
     */
    data class Sky(
        val minuteOfDay: Int,
        val sunriseMinute: Int = 6 * 60,
        val sunsetMinute: Int = 18 * 60,
        val currentC: Double? = null,
        val highC: Double? = null,
        val highAtMinute: Int? = null,
        val lowC: Double? = null,
        val lowAtMinute: Int? = null,
        val cloudCover: Float? = null,
        /** What the sky is doing, as far as the dial can show it. */
        val condition: Weather = Weather.CLEAR,
        /**
         * Cloud cover hour by hour, 0 to 1, indexed by the hour of the
         * local day. Empty where only the current reading is known.
         *
         * What lets the ring say "cloudy from two until five" rather
         * than "cloudy", which is the one thing a dial can tell you
         * that a line of text cannot.
         */
        val hourlyCloud: List<Float> = emptyList(),
        /** What the sky is doing hour by hour, indexed the same way. */
        val hourlyCondition: List<Weather> = emptyList(),
        /** The place's own time zone, where the forecast named one.
         *  A dial set to somewhere else has to run on that somewhere
         *  else's clock or its day lands in the wrong half of the ring. */
        val zoneId: String? = null,
        /** How far round from the sun the moon has got, in degrees:
         *  0 is new and 180 is full. Null before anything works it out. */
        val moonElongationDeg: Double? = null,
        val dateLabel: String = "",
        /** How long dawn and dusk take here. Longer away from the
         *  equator, where the sun goes down at a shallower angle. */
        val twilight: Int = 60,
        /** No sunrise or sunset at all today, which happens past the
         *  polar circles and which a four-band ring cannot show. */
        val polar: Boolean = false,
        val polarDay: Boolean = false,
    ) {
        /** How long the sun is up, in minutes. Zero through a polar
         *  night and the whole day through a polar summer. */
        val daylightMinutes: Int
            get() = when {
                polar -> if (polarDay) MINUTES_IN_DAY else 0
                else -> forward(sunriseMinute, sunsetMinute)
            }

        val warmth: Float get() = warmth(currentC)
        val overcast: Float get() = overcast(cloudCover)
        val sunUp: Boolean
            get() = isUpAt(minuteOfDay, sunriseMinute, sunsetMinute, polar, polarDay)

        /**
         * Whether the moon is above the horizon.
         *
         * Taken against the sun's own rising and setting, which is an
         * approximation: the moon's path is tilted a little off the
         * sun's, so it actually rises and sets up to about an hour
         * either side of where this says. Near enough for a dial that
         * is deciding whether to draw it at all, and far better than
         * drawing one that is not there — a new moon keeps the sun's
         * hours, so on the night somebody goes looking for it there is
         * genuinely no moon in the sky.
         */
        val moonUp: Boolean
            get() {
                val elongation = moonElongationDeg ?: return false
                return isUpAt(
                    Moon.dialMinute(minuteOfDay, elongation),
                    sunriseMinute,
                    sunsetMinute,
                    polar,
                    polarDay,
                )
            }

        /**
         * Whether the moon is worth drawing, which is not the same as
         * being above the horizon.
         *
         * Around a new moon the moon rises and sets with the sun, so
         * [moonUp] is perfectly true all day while there is nothing
         * whatsoever to see: a sliver a fraction of a percent lit,
         * a few degrees from the sun, lost in the glare. Drawing it
         * put two markers on top of each other and claimed a moon
         * that nobody could go outside and find.
         *
         * Below [MOON_MIN_ELONGATION_DEG] of the sun, no moon. The
         * eye's own limit is lower -- a crescent under about seven
         * degrees is never visible at all -- but a dial has a second
         * problem the sky does not: at this separation the two
         * markers are less than an hour apart on a twenty-four hour
         * ring and simply collide.
         */
        val moonVisible: Boolean
            get() {
                val e = moonElongationDeg ?: return false
                val fromSun = minOf(e, 360.0 - e)
                return moonUp && fromSun >= MOON_MIN_ELONGATION_DEG
            }

        /** True when the high and low are far enough apart in time to
         *  mark separately; otherwise one mark would sit on the other. */
        val marksDistinct: Boolean
            get() {
                val a = highAtMinute ?: return false
                val b = lowAtMinute ?: return false
                val gap = abs(a - b)
                return minOf(gap, MINUTES_IN_DAY - gap) >= 45
            }
    }
}

/**
 * Where the dial thinks you are.
 *
 * [fromGps] matters for what the interface says: a place the device
 * found is not the same as a place somebody typed, and offering to
 * change one that was typed reads very differently from overriding
 * one that was measured.
 */
data class HomePlace(
    val lat: Double,
    val lon: Double,
    val label: String,
    val fromGps: Boolean = false,
    /** Height above sea level, where the device knows it. It moves the
     *  horizon: from higher up the sun clears it earlier and the dark
     *  part of the dial shrinks. */
    val elevationMetres: Double? = null,
    /**
     * The place's own time zone, as an IANA id.
     *
     * Load-bearing for anywhere that is not where you are. The sun's
     * times come out of the solar geometry in UTC and are shifted into
     * a local clock; shift them by the *device's* offset while reading
     * a *remote* place's coordinates and the whole lit arc rotates —
     * fifteen degrees of dial per hour of error, so New Zealand seen
     * from the US east coast lands very nearly upside down.
     *
     * Null where nothing knows it: a device fix (where the device's own
     * zone is right by definition) and typed coordinates (where the
     * forecast fills it in on the first fetch).
     */
    val timeZoneId: String? = null,
)

/**
 * Turning a typed place into coordinates.
 *
 * Null where nothing is wired, which is the honest default: geocoding
 * means handing somebody's town to a third party, and that is a
 * decision to take deliberately rather than to inherit from a widget.
 */
typealias PlaceLookup = suspend (String) -> Result<List<HomePlace>>

/**
 * Storing a place, as one line.
 *
 * A string rather than a table because there is exactly one of these
 * and it is settings, not data. Round-tripping is what matters: a
 * stored place that comes back slightly different is a dial that
 * silently moves somewhere else.
 */
object HomePlaceCodec {
    /** Tags the six-field layout. Lines without it are the older
     *  five-field one and still decode, because throwing away somebody's
     *  saved location to add a field would be a poor trade. */
    private const val V2 = "v2"

    /** `v2,lat,lon,fromGps,elevation,zone,label` — the label last,
     *  because it is the only part that can contain anything, commas
     *  included. */
    fun encode(p: HomePlace): String =
        listOf(
            V2,
            p.lat.toString(),
            p.lon.toString(),
            if (p.fromGps) "1" else "0",
            p.elevationMetres?.toString() ?: "",
            p.timeZoneId.orEmpty(),
            p.label,
        ).joinToString(",")

    fun decode(s: String): HomePlace? {
        if (s.isBlank()) return null
        val v2 = s.startsWith("$V2,")
        val parts = if (v2) s.split(",", limit = 7).drop(1) else s.split(",", limit = 5)
        if (parts.size < 5) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lon = parts[1].toDoubleOrNull() ?: return null
        // Coordinates off the globe mean a corrupt line, and a dial
        // pointed at nowhere is worse than one that admits it has no
        // location at all.
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return HomePlace(
            lat = lat,
            lon = lon,
            label = if (v2) parts[5] else parts[4],
            fromGps = parts[2] == "1",
            elevationMetres = parts[3].toDoubleOrNull(),
            timeZoneId = if (v2) parts[4].takeIf { it.isNotBlank() } else null,
        )
    }
}
