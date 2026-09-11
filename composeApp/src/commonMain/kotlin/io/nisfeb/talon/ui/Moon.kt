package io.nisfeb.talon.ui

import kotlin.math.PI
import kotlin.math.cos

/**
 * Where the moon is, and what shape it is tonight.
 *
 * Deliberately the simple model: a mean synodic month counted from a
 * known new moon. It is out by a few hours at the extremes, because
 * the real orbit is an ellipse and the month is not quite constant —
 * enough to be wrong about the exact minute of a new moon, nowhere
 * near enough to draw the wrong crescent or put it on the wrong side
 * of the dial.
 */
object Moon {

    /** New moon to new moon, in days. */
    const val SYNODIC_DAYS = 29.530588853

    /** A new moon everybody agrees on: 2000-01-06 18:14 UTC. */
    private const val KNOWN_NEW_MOON_MS = 947_182_440_000L

    private const val DAY_MS = 86_400_000.0

    data class Phase(
        /** How far round from the sun the moon has got: 0 at new, 180
         *  at full, 270 at last quarter. */
        val elongationDeg: Double,
        /** How much of the disc is catching the sun, 0 to 1. */
        val illuminated: Float,
        /** Filling rather than emptying. */
        val waxing: Boolean,
    )

    fun phaseAt(epochMillis: Long): Phase {
        val days = (epochMillis - KNOWN_NEW_MOON_MS) / DAY_MS
        var age = days % SYNODIC_DAYS
        if (age < 0) age += SYNODIC_DAYS
        val e = age / SYNODIC_DAYS * 360.0
        return Phase(
            elongationDeg = e,
            illuminated = ((1 - cos(e * PI / 180.0)) / 2).toFloat(),
            waxing = e < 180.0,
        )
    }

    /**
     * Where the moon belongs on a dial whose positions are times of day.
     *
     * The moon lags the sun by its elongation — fifteen degrees to the
     * hour — so a new moon crosses the top with the sun at noon and a
     * full moon rides highest at midnight. That lag is the whole reason
     * the moon cannot just be drawn wherever the sun is not.
     */
    fun dialMinute(minuteOfDay: Int, elongationDeg: Double): Int {
        val lag = (elongationDeg / 360.0 * SkyClock.MINUTES_IN_DAY).toInt()
        val m = (minuteOfDay - lag) % SkyClock.MINUTES_IN_DAY
        return if (m < 0) m + SkyClock.MINUTES_IN_DAY else m
    }
}
