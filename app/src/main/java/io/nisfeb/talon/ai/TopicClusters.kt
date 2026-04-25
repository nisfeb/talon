package io.nisfeb.talon.ai

import kotlin.math.sqrt

/**
 * Tiny k-means implementation used by the per-chat "Topics" panel.
 * Cosine-distance based, deterministic farthest-first init so the UI
 * doesn't shuffle clusters across re-opens.
 *
 * Vectors are assumed L2-normalized (the embedder L2-normalizes by
 * default), so cosine reduces to a dot product.
 */
internal fun kMeansAssign(
    vectors: List<FloatArray>,
    k: Int,
    maxIters: Int = 20,
): IntArray {
    if (vectors.isEmpty()) return IntArray(0)
    val effectiveK = k.coerceAtMost(vectors.size).coerceAtLeast(1)
    val dim = vectors[0].size

    // Farthest-first init: first centroid = vectors[0] (deterministic),
    // each subsequent centroid = the point with the largest min-distance
    // to all already-picked centroids.
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
        // Recompute centroids = mean of cluster members, L2-normalized.
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

/** Dot product (== cosine sim for L2-normalized inputs). */
private fun dotNorm(a: FloatArray, b: FloatArray): Float {
    var dot = 0f
    for (i in a.indices) dot += a[i] * b[i]
    return dot
}

/**
 * For each cluster index, return the input-vector index whose
 * embedding is closest to the cluster's centroid — used to pick a
 * "representative" message label per cluster.
 */
internal fun representativeIndices(
    vectors: List<FloatArray>,
    assignment: IntArray,
    k: Int,
): IntArray {
    val out = IntArray(k) { -1 }
    if (vectors.isEmpty()) return out
    val dim = vectors[0].size
    for (c in 0 until k) {
        val members = (0 until vectors.size).filter { assignment[it] == c }
        if (members.isEmpty()) continue
        val centroid = FloatArray(dim)
        for (i in members) for (d in 0 until dim) centroid[d] += vectors[i][d]
        val inv = 1f / members.size
        for (d in 0 until dim) centroid[d] *= inv
        var bestIdx = members[0]
        var bestSim = -2f
        for (i in members) {
            val s = dotNorm(centroid, vectors[i])
            if (s > bestSim) {
                bestSim = s
                bestIdx = i
            }
        }
        out[c] = bestIdx
    }
    return out
}
