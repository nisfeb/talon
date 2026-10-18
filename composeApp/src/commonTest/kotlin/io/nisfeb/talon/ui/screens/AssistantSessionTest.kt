package io.nisfeb.talon.ui.screens

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
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

    // Stop frees the box at once, whatever the run is waiting on, and
    // names the run it ended so that run can tell its cancel from the host's.
    @Test fun `Stop cancels the run and frees the box at once`() {
        val s = session()
        val run = kotlinx.coroutines.Job()
        s.run = run
        s.busy = true
        s.allowAll = true
        s.pending = Pending(io.nisfeb.talon.ai.ToolCall("c1", "send_message", kotlinx.serialization.json.JsonObject(emptyMap())),
            io.nisfeb.talon.ai.Tool(io.nisfeb.talon.ai.ToolSpec("send_message", "", kotlinx.serialization.json.JsonObject(emptyMap())), true) { "" },
            kotlinx.coroutines.CompletableDeferred())
        s.stop()
        assertTrue(run.isCancelled)
        assertFalse(s.busy)
        assertFalse(s.allowAll)
        assertNull(s.pending)
        assertNull(s.run)
        assertSame(run, s.stopped)
    }

    @Test fun `Stop with no run does nothing`() {
        val s = session()
        s.busy = true
        s.stop()
        assertTrue(s.busy, "nothing to stop: not this one's to free")
        assertNull(s.stopped)
    }
}
