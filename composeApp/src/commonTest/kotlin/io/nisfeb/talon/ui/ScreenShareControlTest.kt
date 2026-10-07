package io.nisfeb.talon.ui

import io.nisfeb.talon.call.ScreenSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The share button: one source shares at once, several are offered, a refusal says so. */
class ScreenShareControlTest {
    private val screen = ScreenSource(1, "Built-in display", isWindow = false)
    private val window = ScreenSource(7, "Notes", isWindow = true)

    private class Calls(val accept: Boolean = true) {
        val sets = mutableListOf<ScreenSource?>()
        suspend fun set(s: ScreenSource?): Boolean = accept.also { sets += s }
    }

    @Test
    fun `one source is shared without a menu`() = runTest {
        val calls = Calls()
        val c = ScreenShareControl({ listOf(screen) }, calls::set)
        c.press(sharing = false)
        assertEquals(listOf<ScreenSource?>(screen), calls.sets)
        assertNull(c.offered)
        assertFalse(c.failed)
    }

    @Test
    fun `several sources are offered, and the pick is shared`() = runTest {
        val calls = Calls()
        val c = ScreenShareControl({ listOf(screen, window) }, calls::set)
        c.press(sharing = false)
        assertEquals(listOf(screen, window), c.offered)
        assertTrue(calls.sets.isEmpty(), "nothing is shared before a pick")
        c.choose(window)
        assertEquals(listOf<ScreenSource?>(window), calls.sets)
        assertNull(c.offered, "the menu closes on a pick")
    }

    @Test
    fun `pressing while sharing stops it`() = runTest {
        val calls = Calls()
        val c = ScreenShareControl({ error("a stop lists nothing") }, calls::set)
        c.press(sharing = true)
        assertEquals(listOf<ScreenSource?>(null), calls.sets)
        assertFalse(c.failed)
    }

    @Test
    fun `nothing to share, or a refused capture, says so`() = runTest {
        val none = ScreenShareControl({ emptyList() }, Calls()::set)
        none.press(sharing = false)
        assertTrue(none.failed)
        val refused = ScreenShareControl({ listOf(screen) }, Calls(accept = false)::set)
        refused.press(sharing = false)
        assertTrue(refused.failed)
        // A later share that starts clears it.
        val calls = Calls()
        val ok = ScreenShareControl({ listOf(screen) }, calls::set)
        ok.press(sharing = false)
        assertFalse(ok.failed)
    }

    @Test
    fun `a source without a title still has a name`() {
        assertEquals("Whole screen", screen.copy(title = " ").label())
        assertEquals("Untitled window", window.copy(title = "").label())
        assertEquals("Notes", window.label())
    }
}
