package io.nisfeb.talon.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import kotlin.test.Test
import kotlin.test.assertEquals

class RevealOnPullTest {
    private val drag = NestedScrollSource.UserInput

    @Test
    fun `tucked away at first, pulled past the top it shows, as far as it goes`() {
        val bar = RevealOnPull(maxPx = 64f)
        assertEquals(0f, bar.shownPx)
        // What the list could not take, past its top.
        assertEquals(Offset(0f, 40f), bar.onPostScroll(Offset.Zero, Offset(0f, 40f), drag))
        assertEquals(40f, bar.shownPx)
        assertEquals(Offset(0f, 24f), bar.onPostScroll(Offset.Zero, Offset(0f, 100f), drag), "no further than all of it")
        assertEquals(64f, bar.shownPx)
    }

    @Test
    fun `scrolling the list on folds it away before the list moves`() {
        val bar = RevealOnPull(maxPx = 64f)
        bar.onPostScroll(Offset.Zero, Offset(0f, 64f), drag)
        assertEquals(Offset(0f, -30f), bar.onPreScroll(Offset(0f, -30f), drag), "the bar takes it, not the list")
        assertEquals(34f, bar.shownPx)
        assertEquals(Offset(0f, -34f), bar.onPreScroll(Offset(0f, -100f), drag), "the rest goes on to the list")
        assertEquals(0f, bar.shownPx)
        assertEquals(Offset.Zero, bar.onPreScroll(Offset(0f, -10f), drag), "tucked away, the list has it all")
    }

    @Test
    fun `scrolling back up within the list leaves it tucked away`() {
        val bar = RevealOnPull(maxPx = 64f)
        // The list took all of it: nothing past the top.
        assertEquals(Offset.Zero, bar.onPostScroll(Offset(0f, 50f), Offset.Zero, drag))
        assertEquals(0f, bar.shownPx)
    }

    @Test
    fun `let go part way, it settles open or shut`() {
        val open = RevealOnPull(maxPx = 64f).apply { onPostScroll(Offset.Zero, Offset(0f, 40f), drag); settle() }
        assertEquals(64f, open.shownPx)
        val shut = RevealOnPull(maxPx = 64f).apply { onPostScroll(Offset.Zero, Offset(0f, 20f), drag); settle() }
        assertEquals(0f, shut.shownPx)
    }
}
