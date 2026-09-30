package io.nisfeb.talon.orrery

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The gate learns which way is which, and stays out of the way until it can. */
class PatternGateTest {
    private fun unit(x: Float, y: Float): FloatArray {
        val n = kotlin.math.sqrt(x * x + y * y)
        return floatArrayOf(x / n, y / n)
    }

    @Test
    fun `too few examples is no gate`() {
        assertNull(PatternGate.centroid(List(PatternGate.MIN_EACH - 1) { unit(1f, 0f) }))
    }

    @Test
    fun `a message nearer the discarded side is not worth a model run`() {
        val yes = PatternGate.centroid(List(6) { unit(1f, 0.1f * it) })!!
        val no = PatternGate.centroid(List(6) { unit(0.1f * it, 1f) })!!
        val gate = PatternGate(yes, no)
        assertTrue(gate.worthAModel(unit(1f, 0.2f)))
        assertFalse(gate.worthAModel(unit(0.2f, 1f)))
        assertTrue(gate.worthAModel(unit(1f, 1f)), "a toss-up gets the model: the margin favours a look")
    }
}
