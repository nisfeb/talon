package io.nisfeb.talon.ui.screens

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A run that ends while the Assistant is elsewhere says so on its icon. */
class AssistantSessionTest {
    private fun session() = AssistantSession(CoroutineScope(Dispatchers.Unconfined))

    @Test fun `a run that ends out of sight dots the icon until the assistant is shown`() {
        val s = session()
        s.seen()
        s.shown = false // left the Assistant
        s.tell()
        assertTrue(s.news.value)
        s.seen()
        assertFalse(s.news.value, "shown, it is seen")
    }

    @Test fun `a run that ends on screen dots nothing`() {
        val s = session()
        s.seen()
        s.tell()
        assertFalse(s.news.value)
    }

}
