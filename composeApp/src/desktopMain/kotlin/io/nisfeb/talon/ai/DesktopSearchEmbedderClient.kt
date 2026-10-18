package io.nisfeb.talon.ai

import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Desktop wiring for the commonMain [SearchEmbedderClient] interface.
 * Mirrors `AndroidSearchEmbedderClient`: wraps the platform embedder +
 * indexer and delegates semantic-search + highlights to the shared
 * helpers in commonMain.
 *
 * Constructed once per ship since the indexer is ship-scoped (a new
 * AppDatabase on switch). The desktop entry point in `App.kt` rebuilds
 * the client whenever its `key(shipKey)` block re-keys.
 */
class DesktopSearchEmbedderClient(
    private val db: AppDatabase,
    private val embedder: DesktopEmbedder,
    private val indexer: DesktopEmbeddingIndexer,
) : SearchEmbedderClient {

    override val progress: StateFlow<IndexProgress> = indexer.progress

    override suspend fun start() = indexer.start()

    override suspend fun stop() = indexer.stopAndJoin()

    override suspend fun semanticSearch(query: String): List<MessageEntity> {
        if (query.length < 2) return emptyList()
        val vec = withContext(Dispatchers.IO) { embedder.embed(query) }
            ?: return emptyList()
        return hydrateHits(io.nisfeb.talon.ai.semanticSearch(vec, db.embeddings()), db.messages()::getOne)
    }

    override suspend fun keywordSearch(terms: List<String>): List<MessageEntity> =
        io.nisfeb.talon.ai.keywordSearch(db, terms)

    override suspend fun embed(text: String): FloatArray? =
        withContext(Dispatchers.IO) { embedder.embed(text) }

    override suspend fun computeHighlights(): List<MessageEntity> =
        io.nisfeb.talon.ai.computeHighlights(db)
}
