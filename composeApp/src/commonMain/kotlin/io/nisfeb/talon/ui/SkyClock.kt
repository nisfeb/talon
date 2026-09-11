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

    /** What part of the day a band of the ring is showing. */
    enum class Band { NIGHT, DAWN, DAY, DUSK }

    /** One drawn arc: where it starts, how far it sweeps, and what it is. */
    data class Arc(val startDeg: Float, val sweepDeg: Float, val band: Band)

    /**
     * The ring, in drawing order.
     *
     * Dawn and dusk are bands rather than lines because that is what
     * they are: the sky takes a while. [twilightMinutes] is how long
     * each takes, split either side of the sun crossing the horizon.
     *
     * A sunrise after a sunset means the polar case — a day with no
     * night, or a night with no day — and the ring becomes one band
     * rather than a set of arcs that cross over each other.
     */
    fun arcs(
        sunriseMinute: Int,
        sunsetMinute: Int,
        twilightMinutes: Int = 60,
    ): List<Arc> {
        if (sunsetMinute <= sunriseMinute) {
            // Nothing sane to draw as four bands. One band is honest.
            return listOf(Arc(0f, 360f, Band.NIGHT))
        }
        val half = (twilightMinutes / 2).coerceAtLeast(1)
        val dawnStart = sunriseMinute - half
        val dawnEnd = sunriseMinute + half
        val duskStart = sunsetMinute - half
        val duskEnd = sunsetMinute + half

        fun arc(fromMin: Int, toMin: Int, band: Band): Arc {
            val start = angleOf(fromMin)
            var sweep = angleOf(toMin) - start
            if (sweep <= 0f) sweep += 360f
            return Arc(start, sweep, band)
        }
        return listOf(
            arc(dawnEnd, duskStart, Band.DAY),
            arc(duskStart, duskEnd, Band.DUSK),
            arc(duskEnd, dawnStart + MINUTES_IN_DAY, Band.NIGHT),
            arc(dawnStart, dawnEnd, Band.DAWN),
        )
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
     * How much the sky is doing something, from 0 (clear) to 1
     * (overcast). Drains the day band's colour rather than painting
     * grey over it, so a cloudy afternoon reads as flat rather than as
     * a different time of day.
     */
    fun overcast(cloudCover: Float?): Float = (cloudCover ?: 0f).coerceIn(0f, 1f)

    /** Where the sun's mark sits, or the moon's after dark. */
    fun markIsSun(minuteOfDay: Int, sunriseMinute: Int, sunsetMinute: Int): Boolean =
        minuteOfDay in sunriseMinute until sunsetMinute

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
        val dateLabel: String = "",
        /** How long dawn and dusk take here. Longer away from the
         *  equator, where the sun goes down at a shallower angle. */
        val twilight: Int = 60,
        /** No sunrise or sunset at all today, which happens past the
         *  polar circles and which a four-band ring cannot show. */
        val polar: Boolean = false,
        val polarDay: Boolean = false,
    ) {
        val warmth: Float get() = warmth(currentC)
        val overcast: Float get() = overcast(cloudCover)
        val sunUp: Boolean
            get() = if (polar) polarDay else markIsSun(minuteOfDay, sunriseMinute, sunsetMinute)

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
)

/**
 * Turning a typed place into coordinates.
 *
 * Null where nothing is wired, which is the honest default: geocoding
 * means handing somebody's town to a third party, and that is a
 * decision to take deliberately rather than to inherit from a widget.
 */
typealias PlaceLookup = suspend (String) -> Result<List<HomePlace>>
