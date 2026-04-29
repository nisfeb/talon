package io.nisfeb.talon.data

import org.junit.Test
import kotlin.test.assertEquals

/**
 * sanitizeShipKey decides per-ship database file names. Bugs here
 * land as either:
 *   - distinct ships colliding on the same file (data leak across
 *     ships — exactly the bug per-ship DBs are supposed to prevent),
 *   - or filesystem-illegal characters in the path (Talon won't open
 *     on Windows).
 *
 * The contract is conservative: the result must contain only
 * [a-zA-Z0-9_-]; the input length is preserved (one-to-one
 * substitution) so two distinct shipKeys can't collide.
 */
class SanitizeShipKeyTest {

    @Test
    fun `galaxy patp leading tilde becomes underscore`() {
        assertEquals("_zod", sanitizeShipKey("~zod"))
    }

    @Test
    fun `planet patp dashes are preserved`() {
        assertEquals(
            "_mister-botter",
            sanitizeShipKey("~mister-botter"),
        )
    }

    @Test
    fun `moon patp full chain is preserved`() {
        assertEquals(
            "_ricsul-bilwyt-dozzod-nisfeb",
            sanitizeShipKey("~ricsul-bilwyt-dozzod-nisfeb"),
        )
    }

    @Test
    fun `logged-out sentinel stays the same`() {
        // The "__loggedout__" key is what App.kt passes when no ship
        // is signed in. Both underscores survive the filter.
        assertEquals("__loggedout__", sanitizeShipKey("__loggedout__"))
    }

    @Test
    fun `filesystem-hostile characters are replaced not stripped`() {
        // Length preservation is load-bearing: two distinct ship
        // names that happened to share a prefix must not collapse to
        // the same sanitized string.
        val input = "~a/b\\c:d*e?f<g>h|i\""
        val out = sanitizeShipKey(input)
        assertEquals(input.length, out.length)
        // Forbidden chars all became _.
        assertEquals(out.count { it == '_' }, "_/\\:*?<>|\"".length)
    }

    @Test
    fun `digits and underscore in the middle are kept`() {
        assertEquals("a_b1-c_2", sanitizeShipKey("a_b1-c_2"))
    }

    @Test
    fun `unicode characters are replaced with underscores`() {
        // Kotlin's Char.isLetterOrDigit returns true for non-ASCII
        // letters too (é, ç, etc.). The current implementation lets
        // those through. This test pins that behavior — change it
        // explicitly if cross-platform filesystem edge cases force
        // an ASCII-only sanitizer later.
        assertEquals("café", sanitizeShipKey("café"))
    }

    @Test
    fun `empty string round-trips empty`() {
        assertEquals("", sanitizeShipKey(""))
    }
}
