package io.nisfeb.talon.ui.screens

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwipeQuotesTest {
    @Test
    fun `quotes a channel post when set, and only then`() {
        assertTrue(shouldQuoteOnSwipe(true, "chat/~zod/general", null))
        assertFalse(shouldQuoteOnSwipe(false, "chat/~zod/general", null), "the setting says thread")
        assertFalse(shouldQuoteOnSwipe(true, "~zod", null), "a DM has no quote to send")
        assertFalse(shouldQuoteOnSwipe(true, "chat/~zod/general", "170"), "a reply is already in a thread")
    }
}
