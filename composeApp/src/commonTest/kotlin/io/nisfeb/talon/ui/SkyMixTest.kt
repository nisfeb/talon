package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkyMixTest {

    private val rise = 6 * 60
    private val set = 18 * 60

    private fun mix(minute: Int) = SkyClock.skyMix(minute, rise, set, twilightMinutes = 60)

    @Test
    fun `the horizon is the hinge`() {
        // Zero at sunrise and sunset, which is where the ring is most
        // coloured. Everything else reads off that.
        assertEquals(0f, mix(rise))
        assertEquals(0f, mix(set))
    }

    @Test
    fun `noon is full day and the small hours are deep night`() {
        assertEquals(1f, mix(12 * 60))
        assertEquals(-1f, mix(0))
    }

    @Test
    fun `the sky slides rather than cuts`() {
        // The whole point of dropping the four flat bands: consecutive
        // minutes near sunrise must differ, and never by a jump.
        var prev = mix(rise - 90)
        for (m in (rise - 89)..(rise + 90)) {
            val now = mix(m)
            assertTrue(now - prev >= -0.0001f, "went backwards at $m")
            assertTrue(now - prev < 0.06f, "jumped at $m: $prev -> $now")
            prev = now
        }
        assertTrue(mix(rise + 60) > mix(rise + 10), "the sky keeps brightening after sunrise")
        assertTrue(mix(rise - 10) > mix(rise - 60), "and was already brightening before it")
    }

    @Test
    fun `night settles more slowly than day breaks`() {
        // Measured, not asserted at one guessed minute: find how long
        // each end takes to reach its full colour.
        val toFullDay = (0..300).first { mix(rise + it) >= 1f }
        val toFullNight = (0..300).first { mix(set + it) <= -1f }
        assertTrue(
            toFullNight > toFullDay,
            "day took ${toFullDay}m, night took ${toFullNight}m",
        )
        assertEquals(1f, mix(rise + 45), "day is settled well before an hour is out")
        assertTrue(mix(set + 45) > -1f, "the sky still holds colour 45 minutes after sunset")
    }

    @Test
    fun `a sunset after midnight still reads as a day`() {
        // Far north in summer, and in the far-flung time zones.
        val late = SkyClock.skyMix(minuteOfDay = 23 * 60, sunriseMinute = 3 * 60, sunsetMinute = 1 * 60)
        assertEquals(1f, late, "23:00 is broad daylight when the sun sets at 01:00")
    }

    @Test
    fun `polar days and nights are one colour all the way round`() {
        for (m in 0 until SkyClock.MINUTES_IN_DAY step 137) {
            assertEquals(1f, SkyClock.skyMix(m, 0, 0, polar = true, polarDay = true))
            assertEquals(-1f, SkyClock.skyMix(m, 0, 0, polar = true, polarDay = false))
        }
    }

    @Test
    fun `a day with no length is night, not a divide by zero`() {
        assertEquals(-1f, SkyClock.skyMix(9 * 60, 6 * 60, 6 * 60))
    }

    @Test
    fun `the mix never leaves its range`() {
        for (m in 0 until SkyClock.MINUTES_IN_DAY) {
            assertTrue(mix(m) in -1f..1f, "out of range at $m")
        }
    }
}
