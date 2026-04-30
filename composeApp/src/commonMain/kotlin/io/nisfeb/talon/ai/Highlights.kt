package io.nisfeb.talon.ai

import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
import kotlin.math.sqrt

/**
 * Build a centroid embedding from the user's bookmarks, then score
 * the rest of the local archive against it. The top scorers are
 * "important" by the user's own pattern — surfaces messages similar
 * to ones they've explicitly flagged worth remembering.
 *
 * Returns empty when fewer than [MIN_BOOKMARKS] bookmarks have
 * embeddings, or when the user hasn't enabled the feature.
 */
internal const val MIN_BOOKMARKS = 5

internal suspend fun computeHighlights(
    db: AppDatabase,
    k: Int = 30,
    minScore: Float = 0.55f,
): List<MessageEntity> {
    val bookmarks = db.bookmarks().all()
    if (bookmarks.size < MIN_BOOKMARKS) return emptyList()
    val bookmarkEmbeddings = bookmarks.mapNotNull {
        db.embeddings().get(it.whom, it.postId)
    }
    if (bookmarkEmbeddings.size < MIN_BOOKMARKS) return emptyList()
    val dim = bookmarkEmbeddings.first().dim
    val centroid = FloatArray(dim)
    for (e in bookmarkEmbeddings) {
        val v = unpackEmbedding(e.vector, e.dim)
        for (i in 0 until dim) centroid[i] += v[i]
    }
    val invN = 1f / bookmarkEmbeddings.size
    for (i in 0 until dim) centroid[i] *= invN
    // L2 normalize so we can use the same cosine-as-dot trick.
    val norm = sqrt(centroid.fold(0f) { acc, x -> acc + x * x })
    if (norm > 0) {
        val invNorm = 1f / norm
        for (i in 0 until dim) centroid[i] *= invNorm
    }
    val excludeKeys = bookmarks.asSequence().map { "${it.whom}:${it.postId}" }.toHashSet()
    val hits = semanticSearch(
        queryVector = centroid,
        embeddings = db.embeddings(),
        k = k * 2,
        minScore = minScore,
    ).filter { "${it.whom}:${it.id}" !in excludeKeys }
    return hits.take(k)
        .mapNotNull { db.messages().getOne(it.whom, it.id) }
        .filterNot { it.isDeleted }
}
