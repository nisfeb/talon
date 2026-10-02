package io.nisfeb.talon.util

import io.nisfeb.talon.urbit.PokeNacked
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * About forty screens put an exception's own message in front of people:
 * request URLs, "HTTP 502 <html>", class names. A failure is said in
 * words now, the error whole kept for Copy error details.
 */
class ProblemOfTest {
    @Test
    fun `a slow ship is said calmly, a refusal as one, and either keeps the error whole`() {
        val slow = problemOf("Couldn't save", kotlinx.io.IOException("Request timeout has expired [url=https://ship.test/~/channel/1]"))
        assertTrue(slow.calm)
        assertEquals("Couldn't save: your ship is slow or out of reach. Try again when it's back.", slow.line)
        assertTrue("url=https://ship.test" in slow.details.orEmpty())

        val refused = problemOf("Couldn't join", PokeNacked("groups", "group-join", "banned"))
        assertFalse(refused.calm)
        assertEquals("Couldn't join: the ship refused it (banned).", refused.line)
        val traced = problemOf("Couldn't join", PokeNacked("groups", "group-join", "/sys/vane/gall/hoon:<[1.2 3.4]>\n/app/groups/hoon"))
        assertEquals("Couldn't join: the ship refused it.", traced.line)
    }

    @Test
    fun `a sentence is said, a dump is not`() {
        assertEquals("Couldn't load: The ship's list of groups could not be read.",
            problemOf("Couldn't load", IllegalStateException("The ship's list of groups could not be read.")).line)
        listOf(
            "Client request(GET https://ship.test/x) invalid: 500",
            "HTTP 502 <html><body>Bad Gateway</body></html>",
            "java.lang.IllegalStateException: boom",
            """{"error":"x"}""",
        ).forEach { raw ->
            assertEquals("Couldn't load: something went wrong.", problemOf("Couldn't load", RuntimeException(raw)).line, raw)
        }
    }
}
