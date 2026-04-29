package io.nisfeb.talon.data

import org.junit.Test
import kotlin.test.assertEquals

/**
 * SQLite LIKE wildcards (% _) are pre-escaped at the call site so
 * the queries can use `ESCAPE '\'` to interpret them as literals.
 * Forgetting this would mean a search for "100%" returns every
 * message — a P1 bug. Pin the contract.
 */
class EscapeLikeNeedleTest {

    @Test
    fun `plain text passes through unchanged`() {
        assertEquals("hello world", escapeLikeNeedle("hello world"))
    }

    @Test
    fun `empty string round-trips empty`() {
        assertEquals("", escapeLikeNeedle(""))
    }

    @Test
    fun `percent is escaped`() {
        assertEquals("100\\%", escapeLikeNeedle("100%"))
    }

    @Test
    fun `underscore is escaped`() {
        assertEquals("foo\\_bar", escapeLikeNeedle("foo_bar"))
    }

    @Test
    fun `backslash is escaped first so we do not double-escape the markers we add`() {
        // Order matters: if percent were escaped before backslash, the
        // resulting `\` from `%` → `\%` would itself get escaped to
        // `\\%` and the LIKE clause would treat the `%` as a literal
        // wildcard again. The current impl escapes \ first.
        assertEquals("a\\\\b", escapeLikeNeedle("a\\b"))
    }

    @Test
    fun `mixed wildcards each get escaped`() {
        assertEquals("a\\%b\\_c", escapeLikeNeedle("a%b_c"))
    }

    @Test
    fun `repeated wildcards each get their own escape`() {
        assertEquals("\\%\\%\\%", escapeLikeNeedle("%%%"))
    }

    @Test
    fun `pre-existing backslash-percent in input becomes double-escaped`() {
        // A user typing literal "\%" in the search box should match
        // a literal "\%" in messages. After escaping, the SQL LIKE
        // sees `\\\%` — `\\` (literal backslash) + `\%` (literal
        // percent), which matches `\%` in the row. Verify the
        // escape pipeline produces that.
        assertEquals("\\\\\\%", escapeLikeNeedle("\\%"))
    }
}
