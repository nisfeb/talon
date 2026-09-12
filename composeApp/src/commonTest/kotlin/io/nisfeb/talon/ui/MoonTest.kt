package io.nisfeb.talon.ui

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `a new moon crosses with the sun`() {
        val p = Moon.phaseAt(1_704_974_220_000L)
        assertTrue(p.illuminated < 0.01f, "illuminated ${p.illuminated}")
        // Same place as the sun, whatever the hour.
        assertTrue(abs(Moon.dialMinute(12 * 60, p.elongationDeg) - 12 * 60) < 32)
        assertTrue(abs(Moon.dialMinute(3 * 60, p.elongationDeg) - 3 * 60) < 32)
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
    fun `the moon lags the sun by a quarter day at first quarter`() {
        val new = 1_704_974_220_000L
        val first = Moon.phaseAt(new + (Moon.SYNODIC_DAYS / 4 * 86_400_000).toLong())
        // Six hours behind: a first-quarter moon transits at sunset,
        // not at noon.
        val lag = (12 * 60 - Moon.dialMinute(12 * 60, first.elongationDeg) + 1440) % 1440
        assertTrue(abs(lag - 6 * 60) < 40, "lag $lag minutes")
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

    @Test
    fun `the same fraction is lit either side of full`() {
        // Anchored on the model's own new moon, not a published one:
        // the mean month sits a few hours off the real thing, and that
        // offset is not what this is testing.
        val base = 1_704_974_220_000L
        val trueNew = base - (ageOf(base) * 86_400_000).toLong()
        val waxing = Moon.phaseAt(trueNew + 7 * 86_400_000L)
        val waning = Moon.phaseAt(trueNew + ((Moon.SYNODIC_DAYS - 7) * 86_400_000).toLong())
        assertEquals(waxing.illuminated, waning.illuminated, 0.01f)
        assertTrue(waxing.waxing && !waning.waxing)
    }
}

class NewMoonVisibilityTest {

    @Test
    fun `a new moon sits practically on top of the sun`() {
        // Which is what made it invisible: unlit, and under a larger
        // marker drawn after it. The arithmetic is right; the drawing
        // had to stop sharing a track.
        val base = 1_704_974_220_000L
        val p = Moon.phaseAt(base)
        val noon = 12 * 60
        val gap = kotlin.math.abs(Moon.dialMinute(noon, p.elongationDeg) - noon)
        assertTrue(gap < 30, "a new moon is ${gap} minutes from the sun on the dial")
        assertTrue(p.illuminated < 0.02f, "and ${p.illuminated * 100}% lit")
    }

    @Test
    fun `the moon and the sun share a position at some point every month`() {
        // So the two markers cannot rely on being apart. Somewhere in
        // the month they coincide, and that is exactly when somebody
        // looks for the moon and reports it missing.
        val new = 1_704_974_220_000L
        val closest = (0..29).minOf { day ->
            val p = Moon.phaseAt(new + day * 86_400_000L)
            val gap = kotlin.math.abs(Moon.dialMinute(0, p.elongationDeg) - 0)
            minOf(gap, 1440 - gap)
        }
        assertTrue(closest < 40, "the closest they come in a month is $closest minutes")
    }

    @Test
    fun `the moon rides inside the sun's track`() {
        // Not a detail: it is the whole of why both can be seen at
        // once. Zero would put them back on one another.
        assertTrue(
            io.nisfeb.talon.ui.screens.MOON_TRACK_INSET > 0.2f,
            "an inset of ${io.nisfeb.talon.ui.screens.MOON_TRACK_INSET} does not clear the sun",
        )
        assertTrue(io.nisfeb.talon.ui.screens.MOON_TRACK_INSET < 0.5f, "and it must stay on the band")
    }
}
