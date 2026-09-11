package io.nisfeb.talon.ui.screens

import io.nisfeb.talon.ui.HOME_ROW_RANGE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The dial is square, so its width sets the clock widget's height.
 * Pinned at one size it ignored every height below its own, and the
 * clock drew identically from two row units to six.
 */
class DialSizeTest {

    @Test
    fun `a taller widget gets a bigger dial`() {
        var last = dialSizeFor(HOME_ROW_RANGE.first)
        var grew = 0
        for (rows in HOME_ROW_RANGE) {
            val now = dialSizeFor(rows)
            assertTrue(now >= last, "shrank going from ${rows - 1} to $rows rows")
            if (now > last) grew++
            last = now
        }
        assertTrue(grew >= 8, "only $grew of the ${HOME_ROW_RANGE.count()} heights changed anything")
    }

    @Test
    fun `no single step is a leap`() {
        // The complaint: one row unit of drag doubled the widget.
        for (rows in HOME_ROW_RANGE.first until HOME_ROW_RANGE.last) {
            val step = dialSizeFor(rows + 1) - dialSizeFor(rows)
            assertTrue(
                step.value <= 60f,
                "going from $rows to ${rows + 1} rows jumps the dial by $step",
            )
        }
    }

    @Test
    fun `the smallest still has room to write the time across it`() {
        assertTrue(dialSizeFor(HOME_ROW_RANGE.first).value >= 100f)
    }

    @Test
    fun `the largest stops before it is just a big circle`() {
        assertEquals(dialSizeFor(HOME_ROW_RANGE.last), dialSizeFor(HOME_ROW_RANGE.last + 20))
        assertTrue(dialSizeFor(HOME_ROW_RANGE.last).value <= 440f)
    }

    @Test
    fun `nonsense row counts still give a drawable dial`() {
        for (rows in listOf(-5, 0, 1, 99)) {
            val d = dialSizeFor(rows)
            assertTrue(d.value in 100f..440f, "$rows rows gave $d")
        }
    }
}
