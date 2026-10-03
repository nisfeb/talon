package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class PointerWordsTest {
    // Hints said "Tap" and "Long-press" on a desktop with a mouse.
    @Test
    fun `hints say what the hand does on this platform`() {
        assertEquals(if (isTouchPrimary) "Tap" else "Click", tapWord)
        assertEquals(if (isTouchPrimary) "Long-press" else "Right-click", holdWord)
    }
}
