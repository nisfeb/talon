package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MoonVisibilityTest {

    /** Noon, sun up 06:00-18:00, moon at the given elongation. */
    private fun sky(elongationDeg: Double, minuteOfDay: Int = 12 * 60) =
        SkyClock.Sky(
            minuteOfDay = minuteOfDay,
            sunriseMinute = 6 * 60,
            sunsetMinute = 18 * 60,
            moonElongationDeg = elongationDeg,
        )

    @Test
    fun `a new moon beside the sun is not drawn`() {
        // 2026-09-12: 0.66 days old, half a percent lit, eight degrees
        // from the sun. Genuinely above the horizon and genuinely
        // impossible to see, which is what put two markers on top of
        // each other on the dial.
        val s = sky(8.1)
        assertTrue(s.moonUp, "it really is above the horizon")
        assertFalse(s.moonVisible, "but there is nothing to see")
    }

    @Test
    fun `a moon well clear of the sun is drawn`() {
        assertTrue(sky(90.0, minuteOfDay = 18 * 60).moonVisible)
    }

    @Test
    fun `an old moon closing back on the sun is not drawn`() {
        // Elongation runs 0..360, so the end of the cycle is as close
        // to the sun as the start.
        assertFalse(sky(354.0).moonVisible)
    }

    @Test
    fun `the threshold is the only thing between the two cases`() {
        val below = SkyClock.MOON_MIN_ELONGATION_DEG - 0.1
        val above = SkyClock.MOON_MIN_ELONGATION_DEG + 0.1
        assertFalse(sky(below).moonVisible)
        assertTrue(sky(above).moonVisible)
    }

    @Test
    fun `a moon below the horizon is never drawn however full`() {
        // Full moon at noon: opposite the sun, so under the ground.
        val s = sky(180.0, minuteOfDay = 12 * 60)
        assertFalse(s.moonUp)
        assertFalse(s.moonVisible)
    }

    @Test
    fun `no moon data means no moon`() {
        assertFalse(SkyClock.Sky(minuteOfDay = 0).moonVisible)
    }
}
