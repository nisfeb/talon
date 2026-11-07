package io.nisfeb.talon.ui.screens

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A phone's message menu opens at the finger, on the side with the
 * room, and stays in the window. The rarer actions go a level down,
 * except a level that would hold one.
 */
class TouchMenuTest {
    private val window = IntSize(1000, 2000)
    private val popup = IntSize(600, 800)
    private fun place(x: Int, y: Int, above: Boolean = opensAbove(y.toFloat(), window.height), size: IntSize = popup) =
        placeTouchMenu(IntOffset(x, y), above, window, size, gap = 10, marginX = 20, marginY = 100)

    @Test
    fun `a tap in the upper half opens below the finger, and one in the lower half above it`() {
        assertFalse(opensAbove(999f, 2000))
        assertFalse(opensAbove(1000f, 2000))
        assertTrue(opensAbove(1001f, 2000))
        assertEquals(IntOffset(200, 510), place(500, 500), "the top edge just under the finger")
        assertEquals(IntOffset(200, 1500 - 10 - 800), place(500, 1500), "the bottom edge just over it")
    }

    @Test
    fun `it is centred on the finger and kept inside the window`() {
        assertEquals(20, place(30, 500).x, "a tap at the left edge")
        assertEquals(1000 - 20 - 600, place(990, 500).x, "a tap at the right edge")
    }

    @Test
    fun `one too tall for its side slides back over the finger, and does not flip`() {
        val tall = IntSize(600, 1200)
        assertEquals(2000 - 100 - 1200, place(500, 900, size = tall).y, "below, it stops at the bottom margin")
        assertEquals(100, place(500, 1100, size = tall).y, "above, it stops at the top margin")
        assertEquals(100, place(500, 1500, size = IntSize(600, 1900)).y, "taller than the window, it starts at the top margin")
    }

    @Test
    fun `a group of one is its item, and a group of more is one row`() {
        val a = MenuAct("a", {})
        val b = MenuAct("b", {})
        assertEquals(emptyList(), group("More", emptyList()))
        assertEquals(listOf<TouchEntry>(a), group("More", listOf(a)))
        val g = assertIs<MenuGroup>(group("More", listOf(a, b)).single())
        assertEquals("More", g.label)
        assertEquals(listOf(a, b), g.items)
    }

    @Test
    fun `the quick reactions put the used first, take each once, and stop at the count`() {
        assertEquals(listOf("🦊", ":thumbsup:", ":heart:"), suggestedReactions(listOf("🦊"), 3))
        assertEquals(listOf("❤️", ":thumbsup:", ":laughing:"), suggestedReactions(listOf("❤️"), 3), "a used heart and the palette's are one")
        assertEquals(6, suggestedReactions(emptyList(), 6).size)
    }
}
