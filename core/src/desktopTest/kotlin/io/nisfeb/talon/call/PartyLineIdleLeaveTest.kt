package io.nisfeb.talon.call

import io.ktor.client.HttpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Letting go of a line never joined is quiet. With no socket the null
 * close read as a timeout, and every screen that let go of an idle line
 * logged "the socket did not close in time" (on a phone, each copy of the
 * app as it went away).
 */
class PartyLineIdleLeaveTest {

    @Test
    fun `leaving a line never joined says nothing of a socket`() = runBlocking<Unit> {
        val err = ByteArrayOutputStream()
        val was = System.err
        System.setErr(PrintStream(err, true))
        try {
            val line = PartyLine(HttpClient(), links = { _, _ -> error("no links on an idle line") })
            line.leave()
            // The goodbye and teardown run on the line's own scope.
            delay(PartyLine.GOODBYE_MS * 2)
        } finally {
            System.setErr(was)
        }
        val said = err.toString()
        assertTrue("closing the line" in said, "teardown ran: $said")
        assertFalse("did not close in time" in said, said)
    }
}
