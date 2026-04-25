package io.nisfeb.talon.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class SemanticSearchTest {

    private fun normalize(v: FloatArray): FloatArray {
        val n = sqrt(v.fold(0f) { acc, x -> acc + x * x })
        return if (n == 0f) v else FloatArray(v.size) { v[it] / n }
    }

    @Test
    fun `cosine of identical vectors is 1`() {
        val v = floatArrayOf(0.1f, 0.2f, 0.3f)
        assertEquals(1f, cosine(v, v), 1e-5f)
    }

    @Test
    fun `cosine of orthogonal vectors is 0`() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(0f, 1f, 0f)
        assertEquals(0f, cosine(a, b), 1e-5f)
    }

    @Test
    fun `cosine of opposite vectors is -1`() {
        val a = floatArrayOf(1f, 2f, 3f)
        val b = floatArrayOf(-1f, -2f, -3f)
        assertEquals(-1f, cosine(a, b), 1e-5f)
    }

    @Test
    fun `cosine handles zero vector without divide-by-zero`() {
        val a = floatArrayOf(0f, 0f, 0f)
        val b = floatArrayOf(1f, 2f, 3f)
        assertEquals(0f, cosine(a, b), 1e-5f)
    }

    @Test
    fun `cosine throws on dim mismatch`() {
        val a = floatArrayOf(1f, 2f)
        val b = floatArrayOf(1f, 2f, 3f)
        var caught = false
        try { cosine(a, b) } catch (_: IllegalArgumentException) { caught = true }
        assertTrue("dim mismatch should throw", caught)
    }

    // ─── pack/unpack round-trip ─────────────────────────────────

    @Test
    fun `Embedder pack and unpack round-trip`() {
        val v = floatArrayOf(0.1f, -0.2f, 3.5f, Float.MAX_VALUE, Float.MIN_VALUE, 0f)
        val bytes = Embedder.pack(v)
        val back = Embedder.unpack(bytes, v.size)
        assertEquals(v.size, back.size)
        for (i in v.indices) assertEquals(v[i], back[i], 0f)
    }

    @Test
    fun `pack preserves byte size of dim times 4`() {
        val v = FloatArray(384) { it.toFloat() }
        val bytes = Embedder.pack(v)
        assertEquals(384 * 4, bytes.size)
    }
}
