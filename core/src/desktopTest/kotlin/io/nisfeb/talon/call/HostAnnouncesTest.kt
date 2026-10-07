package io.nisfeb.talon.call

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A host that pushes its roster (wire 9) is asked once and then heard;
 * the party lines list polls only a host never seen to. Telling them
 * apart: an %on-line with no ask of ours waiting was not an answer.
 */
class HostAnnouncesTest {

    /** Our own ship speaks wire 10, so it relays who-is-on. */
    private fun harness() = TrunkHarness().apply {
        scryBody = { if (it.endsWith("/version.json")) """{"wire":10}""" else "{}" }
    }

    private suspend fun started(h: TrunkHarness, c: CallController) {
        c.start()
        h.awaitConnected()
        h.await { c.wire.value == 10 }
    }

    @Test
    fun `an answer to our ask does not mark a host as announcing, a push does`() = runBlocking<Unit> {
        val h = harness()
        val c = CallController(h.session, CallEngineProvider { error("no media needed for presence") })
        try {
            started(h, c)
            c.whoIsOn("~zod", "lounge")
            h.emitFact("""{"on-line":{"from":"~zod","name":"lounge","who":["~bud"]}}""")
            h.await { c.onLine.value["~zod/lounge"] == setOf("~bud") }
            assertFalse(c.announces("~zod"), "an answer, not a push")

            h.emitFact("""{"on-line":{"from":"~wes","name":"den","who":["~bud"]}}""")
            h.await { c.onLine.value["~wes/den"] != null }
            assertTrue(c.announces("~wes"), "nobody asked ~wes")
        } finally {
            c.stop()
        }
    }

    // A wire-8 host answers only. Taken for a pusher, it was never polled
    // again and its roster went stale for the session: an answer that came
    // late (a busy host) or was replayed on a resumed channel read as a push.
    @Test
    fun `an answer answers its ask however late, and only once`() = runBlocking<Unit> {
        val h = harness()
        val c = CallController(h.session, CallEngineProvider { error("no media needed for presence") })
        try {
            started(h, c)
            c.whoIsOn("~zod", "lounge")
            c.whoIsOn("~zod", "den")
            // The stream drops, and the answers come on the resumed one.
            val before = h.streams.get()
            h.endStream()
            h.await(10_000) { h.streams.get() > before }
            h.emitFact("""{"on-line":{"from":"~zod","name":"den","who":["~bud"]}}""")
            h.emitFact("""{"on-line":{"from":"~zod","name":"lounge","who":["~bud"]}}""")
            h.await { c.onLine.value["~zod/lounge"] != null && c.onLine.value["~zod/den"] != null }
            assertFalse(c.announces("~zod"), "two asks, two answers")

            // Nothing waiting: this one the host sent on its own.
            h.emitFact("""{"on-line":{"from":"~zod","name":"lounge","who":[]}}""")
            h.await { c.onLine.value["~zod/lounge"] == emptySet<String>() }
            assertTrue(c.announces("~zod"))
        } finally {
            c.stop()
        }
    }
}
