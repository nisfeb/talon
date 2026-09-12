package io.nisfeb.talon.ui.screens

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StarFieldTest {

    private val ring = 51f // a 320dp dial's band

    @Test
    fun `a star stays where it was put`() {
        // The canvas recomposes every few seconds. Stars that moved
        // between frames would be a fault, not a sky.
        for (i in 0 until STAR_COUNT) {
            assertEquals(starNoise(i, 1), starNoise(i, 1))
            assertEquals(starNoise(i, 2), starNoise(i, 2))
        }
    }

    @Test
    fun `the scatter is a scatter`() {
        // A hash that collapsed would stack every star in one place.
        val along = (0 until STAR_COUNT).map { starNoise(it, 1) }
        assertTrue(along.toSet().size > STAR_COUNT * 0.9, "positions repeat: ${along.toSet().size} distinct")
        assertTrue(along.min() < 0.2f && along.max() > 0.8f, "they bunch in the middle of the night")
        // And the salts must not simply agree with each other, or every
        // star sits on one diagonal.
        val pairs = (0 until STAR_COUNT).count { abs(starNoise(it, 1) - starNoise(it, 2)) < 0.02f }
        assertTrue(pairs < 4, "$pairs stars have the same value from both salts")
    }

    @Test
    fun `noise stays in range, including for salts nobody uses yet`() {
        for (i in 0 until 500) {
            for (salt in 0 until 8) {
                val n = starNoise(i, salt)
                assertTrue(n in 0f..1f, "star $i salt $salt gave $n")
            }
        }
    }

    @Test
    fun `every star fits inside the band`() {
        // The same fault the clouds shipped with twice: a dot whose
        // offset plus its own size crosses the rim gets clipped into a
        // half moon, which reads as dirt.
        for (i in 0 until STAR_COUNT) {
            val reach = abs(starOffset(starNoise(i, 2), ring)) + starRadius(starNoise(i, 4), ring)
            assertTrue(reach < ring / 2f, "star $i reaches $reach of a ${ring / 2f} half-band")
        }
    }

    @Test
    fun `no star is drawn smaller than a pixel`() {
        // A dot under a pixel across is spread over its neighbours by
        // antialiasing and dimmed to match, so a speck that was faint
        // to begin with comes out as nothing. A 320-pixel dial on a
        // plain screen gives a band of about fifty pixels.
        for (ring in listOf(20f, 51f, 144f)) {
            for (i in 0 until STAR_COUNT) {
                val r = starRadius(starNoise(i, 4), ring)
                assertTrue(r >= STAR_MIN_RADIUS_PX, "star $i on a ${ring}px band has radius $r")
            }
        }
    }

    @Test
    fun `the floor does not bind on a screen with pixels to spare`() {
        // Where there is room, the variation is the point.
        val big = (0 until STAR_COUNT).map { starRadius(starNoise(it, 4), 400f) }
        assertTrue(big.min() > STAR_MIN_RADIUS_PX, "the floor is flattening a large dial")
        assertTrue(big.max() / big.min() > 1.5f, "the sizes stopped varying")
    }

    @Test
    fun `a star is a speck`() {
        // Under a hundredth of the band either way. This caught an
        // order of magnitude once and is here to catch the next one.
        for (i in 0 until STAR_COUNT) {
            val r = starRadius(starNoise(i, 4), ring)
            // A speck in proportion, except where the pixel floor has
            // to override it — a band this small cannot hold a
            // proportional speck and a drawable one at once.
            val cap = maxOf(ring * 0.015f, STAR_MIN_RADIUS_PX)
            assertTrue(r <= cap, "star $i has radius $r on a $ring band, cap $cap")
        }
    }

    @Test
    fun `stars come out as the sky goes`() {
        // Fading in through dusk rather than switching on at sunset.
        assertEquals(0f, starBrightness(0f, 0f, 1f), "none at the horizon")
        assertTrue(starBrightness(0.4f, 0f, 1f) < starBrightness(1f, 0f, 1f))
        assertTrue(starBrightness(1f, 0f, 1f) > 0.2f, "deep night should actually show them")
    }

    @Test
    fun `cloud puts them out`() {
        val clear = starBrightness(1f, 0f, 1f)
        assertTrue(starBrightness(1f, 0.5f, 1f) < clear)
        assertTrue(starBrightness(1f, 1f, 1f) < 0.12f, "an overcast night shows next to none")
    }

    @Test
    fun `none of them is bright`() {
        // Subtle was the request. Anything approaching the moon's own
        // white would be a different feature.
        for (i in 0 until STAR_COUNT) {
            val a = starBrightness(1f, 0f, starNoise(i, 3))
            assertTrue(a <= STAR_ALPHA, "star $i at $a")
            assertTrue(a > 0.05f, "star $i at $a would never be seen at all")
        }
        assertTrue(STAR_ALPHA < 0.7f, "STAR_ALPHA $STAR_ALPHA is not subtle")
    }
}
