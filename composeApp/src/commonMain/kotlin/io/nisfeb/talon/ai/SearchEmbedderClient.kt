package io.nisfeb.talon.ai

import io.nisfeb.talon.data.MessageEntity
import kotlinx.coroutines.flow.StateFlow

/**
 * Optional Android-only ML surface that SearchScreen consumes when
 * the user has enabled smart search / highlights. Desktop and any
 * other target without on-device ML pass null and the screen
 * renders text-search-only mode.
 *
 * The Android impl (built around [Embedder] + [EmbeddingIndexer])
 * lives in app/ today and migrates to composeApp/androidMain in a
 * later Stage F task. The interface stays small + non-Compose so
 * the implementation can be kept on its own thread without dragging
 * UI plumbing into the math.
 */
interface SearchEmbedderClient {
    /** Background-indexing progress for the status line. */
    val progress: StateFlow<IndexProgress>

    /** Kick off catch-up indexing. No-op if already running. */
    suspend fun start()

    /** Embed the query, run cosine-sim search, return hit messages
     *  (most-similar first). Empty on any failure or short query. */
    suspend fun semanticSearch(query: String): List<MessageEntity>

    /** Bookmarks-driven important-messages highlights — scored
     *  against the user's bookmark centroid. Empty when no
     *  bookmarks exist or the user hasn't enabled the feature. */
    suspend fun computeHighlights(): List<MessageEntity>
}

data class IndexProgress(
    val running: Boolean = false,
    val indexed: Int = 0,
    val total: Int = 0,
)
