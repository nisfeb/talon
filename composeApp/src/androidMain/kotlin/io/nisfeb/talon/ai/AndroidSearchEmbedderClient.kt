package io.nisfeb.talon.ai

import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

/**
 * Android wiring for the commonMain [SearchEmbedderClient] interface.
 * Wraps the on-device [Embedder] (TF Lite text encoder) plus the
 * [EmbeddingIndexer] catch-up backfill, exposes the indexer progress
 * as the commonMain [IndexProgress] shape, and delegates semantic
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _progress = MutableStateFlow(IndexProgress())
    override val progress: StateFlow<IndexProgress> = _progress.asStateFlow()

    init {
        // Mirror the indexer's Progress shape into the
        // commonMain-friendly IndexProgress. Single hot collector for
        // the lifetime of this adapter — no need to re-attach.
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
        val vec = withContext(Dispatchers.Default) { embedder.embed(query) }
            ?: return emptyList()
        val hits = io.nisfeb.talon.ai.semanticSearch(vec, db.embeddings())
        // Hydrate (whom, id) → MessageEntity, dropping any rows the
        // local DB no longer has (e.g. tombstoned mid-search).
        return hits.mapNotNull { db.messages().getOne(it.whom, it.id) }
    }

    override suspend fun computeHighlights(): List<MessageEntity> =
        io.nisfeb.talon.ai.computeHighlights(db)
}
