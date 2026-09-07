package io.nisfeb.talon.relay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RungCallsTest {
    @Test
    fun `answer keeps the entry so the hangup still pushes`() {
        val r = RungCalls(maxAgeMs = 60_000, answeredMaxAgeMs = 3_600_000)
        r.rang("c1", "ep", "ios", nowMs = 0)
        assertEquals(RungCalls.Target("ep", "ios"), r.settle("c1", answered = true, nowMs = 10_000))
        // Two minutes into the call: past the ring window, inside the answered one.
        assertEquals(RungCalls.Target("ep", "ios"), r.settle("c1", answered = false, nowMs = 130_000))
        assertNull(r.settle("c1", answered = false, nowMs = 131_000), "hangup removes it")
    }

    @Test
    fun `unanswered ring still expires at the ring window`() {
        val r = RungCalls(maxAgeMs = 60_000)
        r.rang("c1", "ep", "android", nowMs = 0)
        assertNull(r.settle("c1", answered = false, nowMs = 61_000))
    }
}
