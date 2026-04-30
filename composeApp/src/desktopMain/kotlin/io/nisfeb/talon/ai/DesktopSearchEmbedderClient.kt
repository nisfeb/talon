package io.nisfeb.talon.ai

import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/**
 * Desktop wiring for the commonMain [SearchEmbedderClient] interface.
 * Mirrors `AndroidSearchEmbedderClient`: wraps the platform embedder +
 * indexer, surfaces the indexer's Progress as the commonMain
 * [IndexProgress] shape, delegates semantic-search + highlights to
 * the shared helpers in commonMain.
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _progress = MutableStateFlow(IndexProgress())
    override val progress: StateFlow<IndexProgress> = _progress.asStateFlow()

    init {
        indexer.progress
            .onEach { p ->
                _progress.value = IndexProgress(
                    running = p.running,
                    indexed = p.indexed,
                    total = p.total,
                )
            }
            .launchIn(scope)
    }

    override suspend fun start() = indexer.start()

    override suspend fun semanticSearch(query: String): List<MessageEntity> {
        if (query.length < 2) return emptyList()
        val vec = withContext(Dispatchers.IO) { embedder.embed(query) }
            ?: return emptyList()
        val hits = io.nisfeb.talon.ai.semanticSearch(vec, db.embeddings())
        return hits.mapNotNull { db.messages().getOne(it.whom, it.id) }
    }

    override suspend fun computeHighlights(): List<MessageEntity> =
        io.nisfeb.talon.ai.computeHighlights(db)
}
