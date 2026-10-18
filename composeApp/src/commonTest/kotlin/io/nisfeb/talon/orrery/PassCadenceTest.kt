package io.nisfeb.talon.orrery

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How far apart the pass loop's passes are. The ten-minute pass ran with
 * the app behind too, reading orrery's whole state for nobody's screen.
 */
class PassCadenceTest {
    private val step = OrreryRepo.PUSH_EVERY_MS

    /** The loop's wait, as startLoop steps it, with [front] saying where the app is after each step. */
    private fun waitOf(first: Long = step, failing: Boolean = false, front: (Int) -> Boolean): Long {
        var left = first
        var waited = 0L
        var n = 0
        while (left > 0) {
            val s = minOf(left, step)
            waited += s
            left = passWaitLeft(left - s, waited, failing, front(n++))
        }
        return waited
    }

    @Test
    fun `in front a pass goes every ten minutes`() {
        assertEquals(10L * 60_000, waitOf { true })
    }

    @Test
    fun `behind a pass goes every half hour`() {
        assertEquals(30L * 60_000, BEHIND_PUSH_EVERY_MS)
        assertEquals(BEHIND_PUSH_EVERY_MS, waitOf { false })
    }

    @Test
    fun `back in front the pass due goes at the next step`() {
        assertEquals(20L * 60_000, waitOf { it >= 1 })
    }

    @Test
    fun `a failing pass waits out its backoff wherever the app is`() {
        val backoff = OrreryRepo.backoff(3) + 30_000
        assertEquals(backoff, waitOf(first = backoff, failing = true) { false })
        assertEquals(backoff, waitOf(first = backoff, failing = true) { true })
    }

    @Test
    fun `a wait left after a failure that cleared ends by the clock behind`() {
        assertEquals(0L, passWaitLeft(left = 50L * 60_000, waited = 40L * 60_000, failing = false, inFront = false))
        assertEquals(10L * 60_000, passWaitLeft(left = 50L * 60_000, waited = 20L * 60_000, failing = false, inFront = false))
        assertEquals(0L, passWaitLeft(left = 50L * 60_000, waited = 10L * 60_000, failing = false, inFront = true))
    }
}
