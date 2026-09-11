package io.nisfeb.talon.ui.screens

import io.nisfeb.talon.ui.SkyClock
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cloud on the dial has been wrong in both directions — hanging off
 * the ring, then shrunk into a row of equidistant emoji — so the shape
 * of it is checked by arithmetic rather than by another look.
 */
class CloudRingTest {

    private val ring = 51f // a 320dp dial's band
    private val radius = 134f // and the circle that band sits on
    private val twelveHourDay = 180f // degrees of ring

    @Test
    fun `every cloud overruns the band and is cut off by it`() {
        // The cropping is the effect. A cloud that fits inside the band
        // is the emoji-in-a-slot version.
        val count = cloudCount(1f, twelveHourDay)
        for (i in 0 until count) {
            val w = cloudBox(ring) * CLOUD_SCALES[i]
            // Checked at the worst angle, not just where it happens to
            // land today: the arc moves with the seasons.
            val least = (0 until 360).minOf { cloudReach(w, it.toFloat()) }
            assertTrue(
                least > ring / 2f,
                "cloud $i reaches $least at its thinnest, band half is ${ring / 2f} — it would not crop",
            )
        }
    }

    @Test
    fun `clouds only go where the sun is`() {
        // The whole point of this pass. Every cloud's centre has to land
        // between sunrise and sunset, whatever the day's length.
        for (dayHours in listOf(4, 8, 12, 16, 20)) {
            val dayMinutes = dayHours * 60
            val rise = 6 * 60
            val count = cloudCount(1f, dayMinutes / 1440f * 360f)
            for (i in 0 until count) {
                val f = cloudFraction(i, count)
                assertTrue(f in 0f..1f, "fraction $f is off the daylight arc")
                val minute = rise + (f * dayMinutes).toInt()
                val mix = SkyClock.skyMix(minute, rise, rise + dayMinutes)
                assertTrue(mix > 0f, "a ${dayHours}h day put cloud $i at $minute, which is not daylight")
            }
        }
    }

    @Test
    fun `the ends of the day are left clear`() {
        // A cloud centred on sunrise hangs half of itself into a night
        // that has no weather drawn in it at all.
        for (count in 1..CLOUD_SCALES.size) {
            for (i in 0 until count) {
                val f = cloudFraction(i, count)
                assertTrue(f >= 0.12f && f <= 0.88f, "cloud $i of $count sits at $f")
            }
        }
    }

    @Test
    fun `a short day carries fewer clouds and a polar night none`() {
        assertEquals(0, cloudCount(1f, 0f), "a polar night has nowhere to put one")
        assertEquals(0, cloudCount(1f, 40f), "and a sliver of a day barely does")
        assertTrue(cloudCount(1f, 120f) < cloudCount(1f, 300f), "a longer day holds more")
        assertTrue(cloudCount(1f, 360f) <= CLOUD_SCALES.size, "a polar day is still capped")
    }

    @Test
    fun `they are not equally spaced`() {
        // Even spacing is what made them read as decoration.
        val count = CLOUD_SCALES.size
        val gaps = (0 until count - 1).map { cloudFraction(it + 1, count) - cloudFraction(it, count) }
        assertTrue(
            (gaps.max() - gaps.min()) > 0.04f,
            "gaps $gaps are near enough equal to look deliberate",
        )
    }

    @Test
    fun `sky still shows between them on a twelve hour day`() {
        // Four clouds this size must not close the lit arc, or the dial
        // stops telling the time.
        val circumference = 2 * PI.toFloat() * radius
        val count = cloudCount(1f, twelveHourDay)
        val covered = (0 until count).sumOf { i ->
            (cloudBox(ring) * CLOUD_SCALES[i] / circumference * 360f).toDouble()
        }
        assertTrue(covered < twelveHourDay * 0.95, "clouds would cover $covered of $twelveHourDay degrees")
    }

    @Test
    fun `they crop against different edges`() {
        // All on the centreline gives identical crescents.
        assertTrue(CLOUD_OFFSETS.any { it < 0f }, "none hug the inner edge")
        assertTrue(CLOUD_OFFSETS.any { it > 0f }, "none hug the outer edge")
        for (o in CLOUD_OFFSETS) assertTrue(o in -0.5f..0.5f, "offset $o is off the band")
    }

    @Test
    fun `the sizes differ enough to read as different clouds`() {
        val hi = CLOUD_SCALES.max()
        val lo = CLOUD_SCALES.min()
        assertTrue(hi / lo > 1.3f, "scales $lo..$hi are one shape repeated")
    }

    @Test
    fun `cover decides how many, and a clear sky has none`() {
        assertEquals(0, cloudCount(0f, twelveHourDay))
        assertEquals(0, cloudCount(0.04f, twelveHourDay))
        assertTrue(cloudCount(0.3f, twelveHourDay) in 1..2, "a partly cloudy sky is not a full ring")
        for (c in 0..20) assertTrue(cloudCount(c / 10f, twelveHourDay) <= CLOUD_SCALES.size)
    }

    @Test
    fun `there is a size, an offset and a nudge for every cloud`() {
        assertEquals(CLOUD_SCALES.size, CLOUD_OFFSETS.size)
        assertEquals(CLOUD_SCALES.size, CLOUD_JITTER.size)
    }
}
