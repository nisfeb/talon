package io.nisfeb.talon.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The clouds shipped twice hanging off both edges of the ring, so the
 * geometry gets a check rather than another look.
 */
class CloudRingTest {

    private val ring = 51f // a 320dp dial's band

    @Test
    fun `every cloud stays inside the band, at every slot and every size`() {
        val box = cloudBox(ring)
        val limit = ring / 2f
        for (i in CLOUD_SLOTS.indices) {
            val w = box * CLOUD_SCALES[i]
            val reach = cloudReach(w, CLOUD_SLOTS[i])
            assertTrue(
                reach <= limit,
                "slot ${CLOUD_SLOTS[i]} at scale ${CLOUD_SCALES[i]}: reaches $reach, band allows $limit",
            )
        }
    }

    @Test
    fun `a cloud sits in the band rather than wedged into it`() {
        // Touching both edges is arithmetically inside and looks like a
        // cloud jammed into a slot. This is the difference between the
        // version that shipped and one worth looking at.
        val w = cloudBox(ring) * (CLOUD_SCALES.maxOrNull() ?: 1f)
        val worst = (0 until 360).maxOf { cloudReach(w, it.toFloat()) }
        assertTrue(worst <= ring * 0.42f, "reaches $worst of a ${ring / 2f} half-band")
    }

    @Test
    fun `the worst angle is still inside the band`() {
        // Not just the slots that happen to be in use: a slot moved
        // later must not quietly break this.
        val w = cloudBox(ring) * (CLOUD_SCALES.maxOrNull() ?: 1f)
        for (a in 0 until 360) {
            assertTrue(cloudReach(w, a.toFloat()) <= ring / 2f, "angle $a reaches ${cloudReach(w, a.toFloat())}")
        }
    }

    @Test
    fun `the sideways case is the tight one`() {
        // Drawn upright, so at three o'clock the glyph's width runs
        // radially and at twelve its height does. Width is the larger,
        // which is what sizing has to respect.
        val w = 40f
        assertTrue(cloudReach(w, 90f) > cloudReach(w, 0f), "three o'clock is the binding case")
    }

    @Test
    fun `cover decides how many, and a clear sky has none`() {
        assertEquals(0, cloudCount(0f))
        assertEquals(0, cloudCount(0.04f))
        assertEquals(CLOUD_SLOTS.size, cloudCount(1f))
        assertTrue(cloudCount(0.3f) in 1..3, "a partly cloudy sky is not a full ring")
        // Never more slots than there are places to put them.
        for (c in 0..20) assertTrue(cloudCount(c / 10f) <= CLOUD_SLOTS.size)
    }

    @Test
    fun `there is a size for every slot`() {
        assertEquals(CLOUD_SLOTS.size, CLOUD_SCALES.size)
    }

    @Test
    fun `no two clouds sit on top of each other`() {
        // A ring of seven that bunched into three would read as three.
        val sorted = CLOUD_SLOTS.sorted()
        for (i in sorted.indices) {
            val next = sorted[(i + 1) % sorted.size]
            val gap = ((next - sorted[i]) + 360f) % 360f
            assertTrue(gap > 20f, "clouds at ${sorted[i]} and $next are ${gap}deg apart")
        }
    }
}
