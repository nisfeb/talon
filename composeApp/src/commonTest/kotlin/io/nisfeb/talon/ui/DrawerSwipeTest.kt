package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The sections drawer's swipe (the hamburger's): only at the top of the
 * back stack, where there is nowhere to go back to. Below it a swipe in
 * from the left is back, and on iOS it opened the drawer instead.
 */
class DrawerSwipeTest {
    @Test
    fun `a swipe opens it only where there is no going back`() {
        assertTrue(drawerSwipes(open = false, backDepth = 0), "the chat list: nothing to go back to")
        assertFalse(drawerSwipes(open = false, backDepth = 1), "a chat: the swipe is back")
        assertFalse(drawerSwipes(open = false, backDepth = 3))
    }

    @Test
    fun `an open drawer can always be swiped or tapped shut`() {
        // Material gates the scrim's tap on the same flag.
        assertTrue(drawerSwipes(open = true, backDepth = 0))
        assertTrue(drawerSwipes(open = true, backDepth = 2))
    }
}
