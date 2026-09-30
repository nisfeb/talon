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
    fun `mixed wildcards each get escaped`() {
        assertEquals("a\\%b\\_c", escapeLikeNeedle("a%b_c"))
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
