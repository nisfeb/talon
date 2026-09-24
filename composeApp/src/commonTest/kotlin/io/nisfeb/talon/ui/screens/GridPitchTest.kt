package io.nisfeb.talon.ui.screens

import io.nisfeb.talon.ui.HOME_COLUMNS
import io.nisfeb.talon.ui.droppedAt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A column's pitch, and what a drag across it comes to.
 *
 * It used to be reported out of the layout pass into a state the drag
 * read, and was still zero when the drag read it — and a pitch of zero
 * is a widget that cannot be moved sideways at all.
 */
class GridPitchTest {

    /** The same arithmetic the page does, in pixels. */
    private fun pitch(pageWidthPx: Float, paddingPx: Float, gapPx: Float): Float {
        val inner = pageWidthPx - paddingPx * 2
        return (inner - gapPx * (HOME_COLUMNS - 1)) / HOME_COLUMNS + gapPx
    }

    @Test
    fun `dragging one pitch moves one column`() {
        val p = pitch(1200f, 16f, 14f)
        assertEquals(4, droppedAt(3, 0, p, 0f, p, 40f).first)
        assertEquals(2, droppedAt(3, 0, -p, 0f, p, 40f).first)
    }

    @Test
    fun `dragging the whole page crosses the whole grid`() {
        // The check that would have caught the zero: a drag from one
        // edge to the other has to be able to cross every column.
        val p = pitch(1200f, 16f, 14f)
        val across = droppedAt(0, 0, 1200f - 32f, 0f, p, 40f).first
        assertTrue(across >= HOME_COLUMNS - 1, "a full-width drag only reached column $across")
    }

}
