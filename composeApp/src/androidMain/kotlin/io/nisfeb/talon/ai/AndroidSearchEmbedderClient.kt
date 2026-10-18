package io.nisfeb.talon.ai

import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Android wiring for the commonMain [SearchEmbedderClient] interface.
 * Wraps the on-device [Embedder] (TF Lite text encoder) plus the
 * [EmbeddingIndexer] catch-up backfill, and delegates semantic
 * search + bookmark-driven highlights to the existing pure helpers.
 *
 * Pre-Stage-F, SearchScreen reached straight into TalonApplication for
 * the embedder + indexer; this adapter restores that wiring through
 * the canonical commonMain interface so the screen can stay
 * platform-agnostic.
 */
class AndroidSearchEmbedderClient(
    private val db: AppDatabase,
    private val embedder: Embedder,
    private val indexer: EmbeddingIndexer,
) : SearchEmbedderClient {

    override val progress: StateFlow<IndexProgress> = indexer.progress

    override suspend fun start() = indexer.start()

    override suspend fun stop() = indexer.stopAndJoin()

    override suspend fun semanticSearch(query: String): List<MessageEntity> {
        if (query.length < 2) return emptyList()
        val vec = withContext(Dispatchers.Default) { embedder.embed(query) }
            ?: return emptyList()
        return hydrateHits(io.nisfeb.talon.ai.semanticSearch(vec, db.embeddings()), db.messages()::getOne)
    }

    override suspend fun keywordSearch(terms: List<String>): List<MessageEntity> =
        io.nisfeb.talon.ai.keywordSearch(db, terms)

    override suspend fun embed(text: String): FloatArray? =
        withContext(Dispatchers.Default) { embedder.embed(text) }

    override suspend fun computeHighlights(): List<MessageEntity> =
        io.nisfeb.talon.ai.computeHighlights(db)
}
