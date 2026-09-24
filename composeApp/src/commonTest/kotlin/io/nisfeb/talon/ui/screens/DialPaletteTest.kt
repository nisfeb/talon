package io.nisfeb.talon.ui.screens

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The three things the dial can put on a night ring -- the sun below
 * the earth, a lit moon, and a moon's unlit limb -- have to stay
 * tellable apart from each other and from the ground they sit on.
 * Pinned because a palette is edited by eye and these relationships
 * are easy to break without noticing.
 */
class DialPaletteTest {

    private fun lum(c: Color): Double {
        fun ch(v: Float): Double {
            val d = v.toDouble()
            return if (d <= 0.04045) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = lum(a); val lb = lum(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    @Test
    fun `the night sun is visible against the night ring`() {
        // Muddy is the failure mode: too little contrast and it reads
        // as a smudge rather than a marker.
        assertTrue(contrast(SUN_DOWN, NIGHT_BASE) > 3.0, "${contrast(SUN_DOWN, NIGHT_BASE)}")
        assertTrue(contrast(SUN_DOWN, NIGHT_OVERCAST) > 2.5)
    }

    @Test
    fun `an unlit limb is the dimmest thing on the ring`() {
        // It has to be there without claiming to be lit.
        assertTrue(contrast(MOON_DARK, NIGHT_BASE) < contrast(SUN_DOWN, NIGHT_BASE))
        assertTrue(contrast(MOON_DARK, NIGHT_BASE) > 1.2, "still visible at all")
    }

    @Test
    fun `the night sun is warm, not olive`() {
        // The old value's failing: at low chroma a yellow hue reads as
        // olive, and olive on indigo is mud. Red must lead clearly.
        assertTrue(SUN_DOWN.red > SUN_DOWN.green * 1.5f)
        assertTrue(SUN_DOWN.green > SUN_DOWN.blue)
    }
}
