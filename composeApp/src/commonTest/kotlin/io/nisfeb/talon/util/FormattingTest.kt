package io.nisfeb.talon.util

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the multiplatform `"%.Nf".format(x)` replacement — it renders
 * coordinates and file sizes, so the digit count, padding, rounding, and
 * negative sign all have to be exactly right on Kotlin/Native too.
 */
class FormattingTest {
    @Test
    fun fixedDigitsAndPadding() {
        assertEquals("1.50", 1.5.formatDecimals(2))
        assertEquals("0.05", 0.05.formatDecimals(2))
        assertEquals("42", 42.0.formatDecimals(0))
        assertEquals("3.14159", 3.14159.formatDecimals(5))
    }

    @Test
    fun roundsToDigits() {
        assertEquals("2.7", 2.66.formatDecimals(1))
        assertEquals("1.00", 0.999.formatDecimals(2))
        assertEquals("1.6 KB", "${1.55.formatDecimals(1)} KB")
    }

    @Test
    fun negativesAndZero() {
        assertEquals("-90.00000", (-90.0).formatDecimals(5))
        assertEquals("0.00", 0.0.formatDecimals(2))
        // Rounds to zero → no spurious minus sign.
        assertEquals("0.00", (-0.0001).formatDecimals(2))
    }
}
