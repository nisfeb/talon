package io.nisfeb.talon.ai

import io.nisfeb.talon.data.EmbeddingDao
import io.nisfeb.talon.data.MessageEmbeddingEntity
import kotlin.math.sqrt

/**
 * Brute-force cosine-similarity scan across every stored embedding,
 * returning the top-K closest hits. Fine up to ~50K rows on a phone
 * (<50ms typically); past that we'd want sqlite-vec or HNSW.
 */
internal data class SemanticHit(
    val whom: String,
    val id: String,
    val score: Float,
)

internal suspend fun semanticSearch(
    queryVector: FloatArray,
    embeddings: EmbeddingDao,
    k: Int = 30,
    minScore: Float = 0.25f,
    pageSize: Int = 1000,
): List<SemanticHit> {
    if (queryVector.isEmpty()) return emptyList()

    // Heap-style top-K via sorted insertion. K is small (≤30) so a
    // sorted ArrayList is faster than overhead of a real PriorityQueue.
    val top = ArrayList<SemanticHit>(k + 1)
    var offset = 0
    while (true) {
        val page = embeddings.page(pageSize, offset)
        if (page.isEmpty()) break
        for (row in page) {
            val v = unpackEmbedding(row.vector, row.dim)
            if (v.size != queryVector.size) continue
            val score = cosine(queryVector, v)
            if (score < minScore) continue
            insertTopK(top, SemanticHit(row.whom, row.id, score), k)
        }
        if (page.size < pageSize) break
        offset += pageSize
    }
    return top
}

/** Cosine similarity. Assumes both vectors are L2-normalized to length
 *  1 (we set L2Normalize=true on the embedder), in which case cosine
 *  reduces to a plain dot product — saves two sqrt() per pair. The
 *  helper still works for un-normalized inputs, just slower. */
internal fun cosine(a: FloatArray, b: FloatArray): Float {
    require(a.size == b.size) { "dim mismatch: ${a.size} vs ${b.size}" }
    var dot = 0f
    var na = 0f
    var nb = 0f
    for (i in a.indices) {
        val x = a[i]
        val y = b[i]
        dot += x * y
        na += x * x
        nb += y * y
    }
    val denom = sqrt(na) * sqrt(nb)
    return if (denom == 0f) 0f else dot / denom
}

private fun insertTopK(top: ArrayList<SemanticHit>, hit: SemanticHit, k: Int) {
    // Reject if list is full and this hit is worse than the worst.
    if (top.size >= k && hit.score <= top.last().score) return
    // Insert at the right spot (sorted descending by score).
    var i = 0
    while (i < top.size && top[i].score > hit.score) i++
    top.add(i, hit)
    while (top.size > k) top.removeAt(top.size - 1)
}
