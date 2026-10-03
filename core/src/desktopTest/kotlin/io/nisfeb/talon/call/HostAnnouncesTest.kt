package io.nisfeb.talon.call

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A host that pushes its roster (wire 9) is asked once and then heard;
 * the party lines list polls only a host never seen to. Telling them
 * apart: an %on-line long after our ask was not its answer.
 */
class HostAnnouncesTest {

    @Test
    fun `an answer to our ask does not mark a host as announcing, a push does`() = runBlocking<Unit> {
        val h = TrunkHarness()
        val c = CallController(h.session, CallEngineProvider { error("no media needed for presence") })
        try {
            c.start()
            h.awaitConnected()
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
}
