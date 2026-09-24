package io.nisfeb.talon.ui.screens

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The shape and size of a cloud on the ring. Where they go is
 * [HourlyWeatherTest]'s business now.
 *
 * Cloud has been wrong in both directions here — hanging off the ring,
 * then shrunk into a row of equidistant emoji — so the geometry is
 * checked by arithmetic rather than by another look.
 */
class CloudRingTest {

    private val ring = 51f // a 320dp dial's band
    private val radius = 134f // and the circle that band sits on

    @Test
    fun `every cloud overruns the band and is cut off by it`() {
        // The cropping is the effect. A cloud that fits inside the band
        // is the emoji-in-a-slot version. Checked at the thinnest angle
        // and the smallest cover, since both move.
        val w = cloudBox(ring) * cloudScale(CLOUD_THRESHOLD)
        val least = (0 until 360).minOf { cloudReach(w, it.toFloat()) }
        assertTrue(
            least > ring / 2f,
            "the smallest cloud reaches $least at its thinnest, band half is ${ring / 2f}",
        )
    }

    @Test
    fun `sky still shows between them at full cover`() {
        // Four clouds this size must not close the ring, or the dial
        // stops telling the time.
        val circumference = 2 * PI.toFloat() * radius
        val covered = CLOUD_MAX * (cloudBox(ring) * cloudScale(1f) / circumference * 360f)
        assertTrue(covered < 300f, "clouds would cover $covered degrees of the ring")
    }

    @Test
    fun `sizes differ enough to read as different clouds`() {
        val hi = cloudScale(1f)
        val lo = cloudScale(CLOUD_THRESHOLD)
        assertTrue(hi / lo > 1.2f, "scales $lo..$hi are one shape repeated")
    }
}
