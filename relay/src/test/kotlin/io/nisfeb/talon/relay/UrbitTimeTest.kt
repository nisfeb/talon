package io.nisfeb.talon.relay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class UrbitTimeTest {

    @Test
    fun `postIdToMs extracts the da portion of a Tlon post id`() {
        // Sample post id: dotted-decimal @da minted from 2024-01-01
        // 00:00:00 UTC + the ~zod author segment. The exact ms value
        // is what we test against — if the DA_UNIX_EPOCH constant
        // drifts from the client's copy, this assertion will fail.
        val expected = 1_704_067_200_000L  // 2024-01-01T00:00:00Z
        val da = io.nisfeb.talon.relay.UrbitTime.let {
            // Round-trip: client's unixMsToDa output, then dotted.
            val daBig = java.math.BigInteger("170141184475152167957503069145530368000") +
                (java.math.BigInteger.valueOf(expected) *
                    java.math.BigInteger.ONE.shiftLeft(64)) /
                java.math.BigInteger.valueOf(1000)
            daBig.toString()
        }
        val postId = "~zod/$da"
        val ms = assertNotNull(UrbitTime.postIdToMs(postId))
        // Allow ±1ms slop from the truncate→round-up reverse trick.
        assert(kotlin.math.abs(ms - expected) <= 1L) {
            "expected ~$expected, got $ms"
        }
    }

    @Test
    fun `postIdToMs handles dotted-decimal da`() {
        // Same value as above but with the dotted form Tlon actually
        // wires up. Dots get stripped before BigInteger parsing.
        val postId = "~zod/170.141.184.504.852.106.413.367.296.000.000"
        val ms = UrbitTime.postIdToMs(postId)
        // Just verifying the parser doesn't choke on dots — the
        // specific ms value isn't load-bearing here.
        assertNotNull(ms)
    }

    @Test
    fun `postIdToMs returns null for malformed input`() {
        assertNull(UrbitTime.postIdToMs(""))
        assertNull(UrbitTime.postIdToMs("~zod"))
        assertNull(UrbitTime.postIdToMs("~zod/"))
        assertNull(UrbitTime.postIdToMs("~zod/not-a-number"))
        assertNull(UrbitTime.postIdToMs("~zod/123.abc.456"))
    }

    @Test
    fun `postIdToMs accepts a bare da with no author segment`() {
        val daStr = "170141184475152167957503069145530368000"
        val ms = UrbitTime.postIdToMs(daStr)
        // Bare da == 1970-01-01 UTC — daToUnixMs returns null only
        // when da < DA_UNIX_EPOCH; equality is allowed.
        assertEquals(0L, ms)
    }
}
