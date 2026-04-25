package io.nisfeb.talon.ai

import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEmbeddingEntity
import io.nisfeb.talon.urbit.StoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Background pass that fills `message_embeddings` from `messages`.
 * Pulls every message the local DB knows about, computes its
 * embedding via [Embedder], and upserts. Idempotent: rows we've
 * already embedded with the same content-hash are skipped on the
 * cheap (no model invocation).
 *
 * One [start] call per app launch; subsequent invocations are no-ops
 * while a prior pass is still running. Live SSE ingest is NOT yet
 * wired through here — the indexer catches up next launch.
 */
class EmbeddingIndexer(
    private val db: AppDatabase,
    private val embedder: Embedder,
    private val scope: CoroutineScope,
) {
    /** State for the small "indexing N / M" UI strip in the search screen. */
    data class Progress(
        val total: Int,
        val indexed: Int,
        val running: Boolean,
    ) {
        val complete: Boolean get() = !running && total > 0 && indexed >= total
    }

    private val _progress = MutableStateFlow(Progress(0, 0, false))
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    @Volatile private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { backfill() }
    }

    fun stop() { job?.cancel() }

    private suspend fun backfill() {
        // We don't want to load every message at once on a chatty
        // archive (could be 50K+ rows). Page through `messages`,
        // check the embedding existence set per page, embed misses.
        val existing = db.embeddings().allKeys().toHashSet()
        var totalSoFar = existing.size
        var indexed = existing.size
        _progress.value = Progress(total = totalSoFar, indexed = indexed, running = true)

        var offset = 0
        val pageSize = 500
        val pendingRows = mutableListOf<MessageEmbeddingEntity>()
        while (true) {
            val page = pageMessages(offset, pageSize)
            if (page.isEmpty()) break
            totalSoFar += page.count { keyOf(it.whom, it.id) !in existing }
            for (m in page) {
                yield()
                val key = keyOf(m.whom, m.id)
                if (key in existing) continue
                if (m.isDeleted) {
                    existing += key
                    continue
                }
                val text = StoryCache.textFor(m.id, m.contentJson)
                    .trim()
                    .take(1000)
                if (text.length < 4) {
                    existing += key
                    continue
                }
                val vec = embedder.embed(text) ?: continue
                pendingRows += MessageEmbeddingEntity(
                    whom = m.whom,
                    id = m.id,
                    vector = Embedder.pack(vec),
                    dim = vec.size,
                    textHash = text.hashCode().toLong(),
                )
                existing += key
                indexed++
                if (pendingRows.size >= 50) {
                    db.embeddings().upsertAll(pendingRows)
                    pendingRows.clear()
                    _progress.value = Progress(totalSoFar, indexed, running = true)
                }
            }
            if (page.size < pageSize) break
            offset += pageSize
        }
        if (pendingRows.isNotEmpty()) db.embeddings().upsertAll(pendingRows)
        _progress.value = Progress(totalSoFar, indexed, running = false)
    }

    /** Pull a page of messages, including deleted rows (we want to
     *  mark them as "seen" in the existence set so the indexer
     *  doesn't try to embed them every launch). */
    private suspend fun pageMessages(offset: Int, limit: Int) =
        db.messages().pageAll(offset, limit)

    private fun keyOf(whom: String, id: String) = "$whom:$id"
}
