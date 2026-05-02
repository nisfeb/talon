package io.nisfeb.talon.ai

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Pack a FloatArray into a little-endian byte blob for SQLite. */
fun packEmbedding(vector: FloatArray): ByteArray {
    val bb = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    for (f in vector) bb.putFloat(f)
    return bb.array()
}

/** Inverse of [packEmbedding] — read a little-endian Float blob. */
fun unpackEmbedding(bytes: ByteArray, dim: Int): FloatArray {
    val out = FloatArray(dim)
    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    for (i in 0 until dim) out[i] = bb.float
    return out
}

/**
 * Tiny k-means implementation used by the per-chat "Topics" panel.
 * Cosine-distance based, deterministic farthest-first init so the UI
 * doesn't shuffle clusters across re-opens.
 *
 * Vectors are assumed L2-normalized (the embedder L2-normalizes by
 * default), so cosine reduces to a dot product.
 */
fun kMeansAssign(
    vectors: List<FloatArray>,
    k: Int,
    maxIters: Int = 20,
): IntArray {
    if (vectors.isEmpty()) return IntArray(0)
    val effectiveK = k.coerceAtMost(vectors.size).coerceAtLeast(1)
    val dim = vectors[0].size

    val centroids = ArrayList<FloatArray>(effectiveK)
    centroids.add(vectors[0].copyOf())
    while (centroids.size < effectiveK) {
        var bestIdx = 0
        var bestDist = -1f
        for (i in vectors.indices) {
            var minDist = Float.POSITIVE_INFINITY
            for (c in centroids) {
                val d = 1f - dotNorm(c, vectors[i])
                if (d < minDist) minDist = d
            }
            if (minDist > bestDist) {
                bestDist = minDist
                bestIdx = i
            }
        }
        centroids.add(vectors[bestIdx].copyOf())
    }

    val assignment = IntArray(vectors.size)
    for (iter in 0 until maxIters) {
        var changed = false
        for (i in vectors.indices) {
            var best = 0
            var bestSim = -2f
            for (c in 0 until effectiveK) {
                val s = dotNorm(centroids[c], vectors[i])
                if (s > bestSim) {
                    bestSim = s
                    best = c
                }
            }
            if (assignment[i] != best) {
                assignment[i] = best
                changed = true
            }
        }
        if (!changed) break
        for (c in 0 until effectiveK) {
            val sum = FloatArray(dim)
            var n = 0
            for (i in vectors.indices) {
                if (assignment[i] != c) continue
                for (d in 0 until dim) sum[d] += vectors[i][d]
                n++
            }
            if (n == 0) continue
            val inv = 1f / n
            for (d in 0 until dim) sum[d] *= inv
            val norm = sqrt(sum.fold(0f) { acc, x -> acc + x * x })
            if (norm > 0) {
                val invNorm = 1f / norm
                for (d in 0 until dim) sum[d] *= invNorm
            }
            centroids[c] = sum
        }
    }
    return assignment
}

/** Dot product. For L2-normalized inputs this equals cosine
 *  similarity — the embedder produces normalized vectors so callers
 *  can use this directly instead of the more expensive [cosine]
 *  helper that re-normalizes. */
internal fun dotNorm(a: FloatArray, b: FloatArray): Float {
    var dot = 0f
    for (i in a.indices) dot += a[i] * b[i]
    return dot
}
