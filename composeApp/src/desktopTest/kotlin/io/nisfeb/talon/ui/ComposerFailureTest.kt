package io.nisfeb.talon.ui

import io.ktor.client.plugins.HttpRequestTimeoutException
import io.nisfeb.talon.urbit.PokeNacked
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the line over the composer says when a write did not go through:
 * a slow ship calmly, a refusal as one, and the error whole only behind
 * "Copy error details". "react failed: Request timeout has expired
 * [url=…, request_timeout=30000 ms]" was the line.
 */
class ComposerFailureTest {
    @Test
    fun `a ship that timed out is said calmly, the error whole behind the button`() {
        val s = ComposerState("")
        s.failed("edit", HttpRequestTimeoutException("https://ship.example/~/channel/1-2", 30_000))
        assertEquals("Your ship is slow, so the edit didn't go through. Try again when it's back.", s.sendError)
        assertTrue(s.sendErrorCalm)
        assertTrue("request_timeout=30000" in s.sendErrorDetails.orEmpty(), s.sendErrorDetails)
        assertFalse("https://" in s.sendError.orEmpty(), "no URL in the line")
    }

    @Test
    fun `a lost connection is slow too, and a refusal is said as one`() {
        val s = ComposerState("")
        s.failed("react", kotlinx.io.IOException("The network connection was lost."))
        assertEquals("Your ship is slow, so the reaction didn't go through. Try again when it's back.", s.sendError)
        s.failed("pin", PokeNacked("channels", "channel-action-2", "bad-key\n~/crash/stack"))
        assertEquals("pin failed: the ship refused it", s.sendError)
        assertFalse(s.sendErrorCalm)
        assertTrue("bad-key" in s.sendErrorDetails.orEmpty())
    }

    @Test
    fun `a plain note has no details, and clearing clears them`() {
        val s = ComposerState("")
        s.failed("edit", kotlinx.io.IOException("gone"))
        s.sendError = "Reported to the group's admins"
        assertNull(s.sendErrorDetails)
        assertFalse(s.sendErrorCalm)
    }
}
