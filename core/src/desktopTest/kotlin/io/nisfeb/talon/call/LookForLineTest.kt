package io.nisfeb.talon.call

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Opening a group with no known line asked its host three times, on
 * every opening: a poke on our ship and an ames message to the host
 * each. It asks once a connect now.
 */
class LookForLineTest {

    @Test
    fun `a line looked for is not looked for again until the next connect`() = runBlocking<Unit> {
        val h = TrunkHarness()
        val c = CallController(h.session, CallEngineProvider { error("no media needed") })
        fun peeks() = h.putsSnapshot().count { "peek-room" in it }
        try {
            c.start()
            h.awaitConnected()
            c.lookForLine("~zod", "lounge")
            assertEquals(CallController.PEEK_ATTEMPTS, peeks(), "a few widening tries")
            c.lookForLine("~zod", "lounge")
            assertEquals(CallController.PEEK_ATTEMPTS, peeks(), "opened again: nothing sent")
        } finally {
            c.stop()
        }
    }
}
