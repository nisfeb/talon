package io.nisfeb.talon.ui

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * When the sun rises and sets where you are.
 *
 * The dial's dark half is not a fixed twelve hours: it depends on how
 * far you are from the equator and where the year has got to. On the
 * equator the two halves stay near even all year; far enough north or
 * south they swap almost entirely, and past the circles one of them
 * swallows the day whole.
 *
 * The NOAA sunrise equation, which is accurate to about a minute at
 * ordinary latitudes and is the one everybody uses. Pure, so the
 * awkward cases can be tested rather than discovered in a screenshot
 * taken in December.
 */
object Solar {

    /**
     * A day's sun. [sunriseMinute] and [sunsetMinute] are local minutes
     * past midnight; [polar] says there is no sunrise or sunset at all,
     * with [polarDay] telling which kind.
     */
    data class SunTimes(
        val sunriseMinute: Int,
        val sunsetMinute: Int,
        val polar: Boolean = false,
        val polarDay: Boolean = false,
    )

    private const val DEG = PI / 180.0

    /**
     * [latitude] and [longitude] in degrees, [dayOfYear] from 1, and
     * the local zone's offset from UTC in minutes.
     *
     * The horizon is taken at 90.833 degrees from vertical rather than
     * 90: the sun's disc has width and the atmosphere bends its light,
     * so it is visible while geometrically still below. Leaving that
     * out puts sunrise several minutes late.
     */
    fun sunTimes(
        latitude: Double,
        longitude: Double,
        dayOfYear: Int,
        zoneOffsetMinutes: Int,
        elevationMetres: Double = 0.0,
    ): SunTimes {
        val gamma = 2.0 * PI / 365.0 * (dayOfYear - 1)
        val eqTime = 229.18 * (
            0.000075 +
                0.001868 * cos(gamma) - 0.032077 * sin(gamma) -
                0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma)
            )
        val decl = 0.006918 -
            0.399912 * cos(gamma) + 0.070257 * sin(gamma) -
            0.006758 * cos(2 * gamma) + 0.000907 * sin(2 * gamma) -
            0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)

        val lat = latitude * DEG
        // Standing higher puts the horizon further down, so the sun
        // clears it earlier and sets later and the dark part of the day
        // shrinks. On a mountain that is worth minutes, not seconds.
        val zenith = 90.833 + horizonDip(elevationMetres)
        val cosHa = cos(zenith * DEG) / (cos(lat) * cos(decl)) - tan(lat) * tan(decl)

        // Out of range means the sun never crosses the horizon that day.
        // Which side it is out on says whether the day or the night is
        // the one that lasts: this is the case a fixed six-to-six dial
        // gets silently and confidently wrong.
        if (cosHa > 1.0) {
            return SunTimes(0, 0, polar = true, polarDay = false)
        }
        if (cosHa < -1.0) {
            return SunTimes(0, SkyClock.MINUTES_IN_DAY, polar = true, polarDay = true)
        }

        val ha = acos(cosHa) / DEG
        val riseUtc = 720.0 - 4.0 * (longitude + ha) - eqTime
        val setUtc = 720.0 - 4.0 * (longitude - ha) - eqTime
        return SunTimes(
            sunriseMinute = wrap(riseUtc + zoneOffsetMinutes),
            sunsetMinute = wrap(setUtc + zoneOffsetMinutes),
        )
    }

    private fun wrap(minutes: Double): Int {
        val m = minutes.toInt() % SkyClock.MINUTES_IN_DAY
        return if (m < 0) m + SkyClock.MINUTES_IN_DAY else m
    }

    /**
     * How long the day lasts, in minutes. A polar day is the whole
     * twenty-four hours and a polar night is none of it.
     */
    fun daylightMinutes(t: SunTimes): Int = when {
        t.polar && t.polarDay -> SkyClock.MINUTES_IN_DAY
        t.polar -> 0
        t.sunsetMinute >= t.sunriseMinute -> t.sunsetMinute - t.sunriseMinute
        // The sun set after local midnight, which happens near a pole
        // in summer and with far-flung time zones.
        else -> SkyClock.MINUTES_IN_DAY - (t.sunriseMinute - t.sunsetMinute)
    }

    /**
     * How far below level the horizon sits, in degrees, for somebody
     * [metres] above sea level.
     *
     * The earth curves away, so from higher up you see over more of it.
     * A thousand metres buys about a degree, which is roughly four
     * minutes of daylight at each end; the summit of Everest buys three.
     * Negative elevations are clamped rather than trusted: below sea
     * level the horizon rises, but a GPS fix reading minus two hundred
     * is far more likely to be wrong than to be the Dead Sea.
     */
    fun horizonDip(metres: Double): Double {
        val h = metres.coerceIn(0.0, 9000.0)
        if (h == 0.0) return 0.0
        val earthRadius = 6_371_000.0
        return acos(earthRadius / (earthRadius + h)) / DEG
    }

    /**
     * Twilight lasts longer the further you are from the equator: the
     * sun goes down at a shallower angle, so the sky takes longer about
     * it. Roughly, and bounded, because the dial only needs a band that
     * looks right rather than a number anybody depends on.
     */
    fun twilightMinutes(latitude: Double): Int {
        val lat = abs(latitude).coerceIn(0.0, 89.0)
        return (22.0 / cos(lat * DEG)).toInt().coerceIn(20, 180)
    }
}
