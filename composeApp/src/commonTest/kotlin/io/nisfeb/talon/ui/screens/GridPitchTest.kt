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
    fun `twelve columns and their gaps fill the page exactly`() {
        val page = 1200f
        val pad = 16f
        val gap = 14f
        val p = pitch(page, pad, gap)
        val used = p * HOME_COLUMNS - gap
        assertEquals(page - pad * 2, used, 0.5f, "the columns do not add up to the page")
    }

    @Test
    fun `a pitch is never zero for a page anybody can see`() {
        for (w in listOf(400f, 820f, 1200f, 3840f)) {
            assertTrue(pitch(w, 16f, 14f) > 1f, "a ${w}px page gave a pitch of ${pitch(w, 16f, 14f)}")
        }
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

    @Test
    fun `a zero pitch is what being stuck looks like`() {
        // Kept as a demonstration: this is exactly what the page did
        // while the pitch was being reported out of the layout pass.
        assertEquals(3, droppedAt(3, 0, 5000f, 0f, 0f, 40f).first)
    }
}
