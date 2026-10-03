package io.nisfeb.talon.orrery

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A beacon stream ~ricsul accepted and then broke at once came back every
 * three seconds: the pause went back to the floor on being let in.
 */
class BeaconBackoffTest {
    /** The pauses taken over [n] streams that each lived [livedMs]. */
    private fun pauses(n: Int, livedMs: Long?): List<Long> {
        var pause = BEACON_FIRST_PAUSE_MS
        return List(n) {
            pause = beaconPauseAfter(pause, livedMs)
            pause.also { pause = (pause * 2).coerceAtMost(5 * 60_000L) }
        }
    }

    @Test
    fun `streams broken at once back off, as streams that never open do`() {
        assertEquals(listOf(3_000L, 6_000L, 12_000L, 24_000L, 48_000L), pauses(5, livedMs = 2_000L))
        assertEquals(pauses(5, livedMs = null), pauses(5, livedMs = 2_000L))
    }

    @Test
    fun `a stream that lived ends with the short pause`() {
        assertEquals(List(4) { 3_000L }, pauses(4, livedMs = BEACON_HEALTHY_MS))
        assertEquals(BEACON_FIRST_PAUSE_MS, beaconPauseAfter(48_000L, livedMs = 10 * 60_000L))
    }
}
