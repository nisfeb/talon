package io.nisfeb.talon.ui.screens

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

    @Test
    fun `every cloud overruns the band and is cut off by it`() {
        // The cropping is the effect. A cloud that fits inside the band
        // is the emoji-in-a-slot version.
        for (i in CLOUD_SLOTS.indices) {
            val w = cloudBox(ring) * CLOUD_SCALES[i]
            val reach = cloudReach(w, CLOUD_SLOTS[i])
            assertTrue(
                reach > ring / 2f,
                "slot ${CLOUD_SLOTS[i]} reaches $reach, band half is ${ring / 2f} — it would not crop",
            )
        }
    }

    @Test
    fun `the ring holds only a few of them`() {
        assertTrue(CLOUD_SLOTS.size <= 4, "${CLOUD_SLOTS.size} is a crowd")
        assertEquals(CLOUD_SLOTS.size, cloudCount(1f))
    }

    @Test
    fun `they are not equally spaced`() {
        // Even spacing is what made them read as decoration.
        val sorted = CLOUD_SLOTS.sorted()
        val gaps = sorted.indices.map { i ->
            ((sorted[(i + 1) % sorted.size] - sorted[i]) + 360f) % 360f
        }
        assertTrue(
            (gaps.max() - gaps.min()) > 15f,
            "gaps ${gaps} are near enough equal to look deliberate",
        )
    }

    @Test
    fun `sky still shows between them at full cover`() {
        // Four clouds this size must not close the ring, or the dial
        // stops telling the time.
        val circumference = 2 * PI.toFloat() * radius
        val covered = CLOUD_SLOTS.indices.sumOf { i ->
            (cloudBox(ring) * CLOUD_SCALES[i] / circumference * 360f).toDouble()
        }
        assertTrue(covered < 300.0, "clouds would cover $covered degrees of the ring")
    }

    @Test
    fun `they crop against different edges`() {
        // All on the centreline gives four identical crescents.
        assertTrue(CLOUD_OFFSETS.any { it < 0f }, "none hug the inner edge")
        assertTrue(CLOUD_OFFSETS.any { it > 0f }, "none hug the outer edge")
        // But none so far off that it leaves the band entirely.
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
        assertEquals(0, cloudCount(0f))
        assertEquals(0, cloudCount(0.04f))
        assertEquals(CLOUD_SLOTS.size, cloudCount(1f))
        assertTrue(cloudCount(0.3f) in 1..2, "a partly cloudy sky is not a full ring")
        for (c in 0..20) assertTrue(cloudCount(c / 10f) <= CLOUD_SLOTS.size)
    }

    @Test
    fun `there is a size and an offset for every slot`() {
        assertEquals(CLOUD_SLOTS.size, CLOUD_SCALES.size)
        assertEquals(CLOUD_SLOTS.size, CLOUD_OFFSETS.size)
    }
}
