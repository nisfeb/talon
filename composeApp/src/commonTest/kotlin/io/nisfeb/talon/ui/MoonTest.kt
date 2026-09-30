package io.nisfeb.talon.ui

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class MoonTest {

    /** Elongation as days of age, which is the easier thing to check. */
    private fun ageOf(ms: Long) = Moon.phaseAt(ms).elongationDeg / 360.0 * Moon.SYNODIC_DAYS

    private fun assertNearNew(ms: Long, what: String) {
        val age = ageOf(ms)
        val off = minOf(age, Moon.SYNODIC_DAYS - age)
        assertTrue(off < 0.6, "$what: off by ${off} days")
    }

    @Test
    fun `known new moons land on new`() {
        // 2024-01-11 11:57 UTC and 2025-03-29 10:58 UTC, both published.
        assertNearNew(1_704_974_220_000L, "2024-01-11")
        assertNearNew(1_743_245_880_000L, "2025-03-29")
    }

    @Test
    fun `a full moon is opposite the sun on the dial`() {
        // Half a synodic month after a new moon.
        val full = 1_704_974_220_000L + (Moon.SYNODIC_DAYS / 2 * 86_400_000).toLong()
        val p = Moon.phaseAt(full)
        assertTrue(abs(p.elongationDeg - 180.0) < 8.0, "elongation ${p.elongationDeg}")
        assertTrue(p.illuminated > 0.99f, "illuminated ${p.illuminated}")
        // Full moon rides highest at midnight: on a dial where noon is
        // the top, that puts it at the noon slot when the clock says 0.
        assertTrue(abs(Moon.dialMinute(0, p.elongationDeg) - 12 * 60) < 32)
    }

    @Test
    fun `the quarters are half lit and on opposite sides`() {
        val new = 1_704_974_220_000L
        val q = (Moon.SYNODIC_DAYS / 4 * 86_400_000).toLong()
        val first = Moon.phaseAt(new + q)
        val last = Moon.phaseAt(new + 3 * q)
        assertTrue(abs(first.illuminated - 0.5f) < 0.02f, "first ${first.illuminated}")
        assertTrue(abs(last.illuminated - 0.5f) < 0.02f, "last ${last.illuminated}")
        assertTrue(first.waxing, "first quarter is filling")
        assertTrue(!last.waxing, "last quarter is emptying")
    }

    @Test
    fun `the phase never leaves its range, however far from the epoch`() {
        for (ms in listOf(0L, -5_000_000_000_000L, 4_000_000_000_000L, 1_757_000_000_000L)) {
            val p = Moon.phaseAt(ms)
            assertTrue(p.elongationDeg in 0.0..360.0, "elongation ${p.elongationDeg} at $ms")
            assertTrue(p.illuminated in 0f..1f, "illuminated ${p.illuminated} at $ms")
            assertTrue(Moon.dialMinute(0, p.elongationDeg) in 0 until 1440)
            assertTrue(Moon.dialMinute(1439, p.elongationDeg) in 0 until 1440)
        }
    }

}

class NewMoonVisibilityTest {

}

class MoonUpTest {

    private fun sky(minute: Int, elongation: Double, rise: Int = 6 * 60, set: Int = 18 * 60) =
        SkyClock.Sky(
            minuteOfDay = minute,
            sunriseMinute = rise,
            sunsetMinute = set,
            moonElongationDeg = elongation,
        )

    @Test
    fun `a full moon is up all night and down all day`() {
        assertTrue(sky(minute = 0, elongation = 180.0).moonUp, "down at midnight")
        assertTrue(sky(minute = 23 * 60, elongation = 180.0).moonUp)
        assertTrue(!sky(minute = 12 * 60, elongation = 180.0).moonUp, "up at midday")
    }

    @Test
    fun `a first quarter moon is up in the evening`() {
        // It transits at sunset, so it holds the early night and has
        // set by the small hours.
        assertTrue(sky(minute = 20 * 60, elongation = 90.0).moonUp, "down at eight")
        assertTrue(!sky(minute = 4 * 60, elongation = 90.0).moonUp, "still up at four")
    }

    @Test
    fun `the polar cases follow the sun`() {
        val night = sky(0, 180.0).copy(polar = true, polarDay = false)
        val day = sky(0, 180.0).copy(polar = true, polarDay = true)
        assertTrue(!night.moonUp)
        assertTrue(day.moonUp)
    }

    @Test
    fun `a sunset after midnight does not put everything below the horizon`() {
        // The plain range check made `rise until set` empty whenever
        // sunset wrapped past midnight, so nothing was ever up.
        val s = SkyClock.Sky(
            minuteOfDay = 23 * 60,
            sunriseMinute = 3 * 60,
            sunsetMinute = 1 * 60,
            moonElongationDeg = 0.0,
        )
        assertTrue(s.sunUp, "the sun is down at eleven on a night it sets at one")
        assertTrue(s.moonUp, "and so is a new moon, which keeps its hours")
    }
}
