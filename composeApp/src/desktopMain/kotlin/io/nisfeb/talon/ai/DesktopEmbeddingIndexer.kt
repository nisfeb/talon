package io.nisfeb.talon.ai

import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEmbeddingEntity
import io.nisfeb.talon.urbit.StoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Desktop counterpart to the Android `EmbeddingIndexer`. Same
 * paginated catch-up shape — load page of messages, compute embedding
 * via [DesktopEmbedder], upsert. Lives in desktopMain because the
 * embedder is platform-specific; the loop body would otherwise be
 * identical to Android.
 */
class DesktopEmbeddingIndexer(
    private val db: AppDatabase,
    private val embedder: DesktopEmbedder,
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    data class Progress(
        val total: Int,
        val indexed: Int,
        val running: Boolean,
    )

    private val _progress = MutableStateFlow(Progress(0, 0, false))
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    @Volatile private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { backfill() }
    }

    fun stop() { job?.cancel() }

    private suspend fun backfill() {
        val existing = db.embeddings().allKeys().toHashSet()
        var totalSoFar = existing.size
        var indexed = existing.size
        _progress.value = Progress(total = totalSoFar, indexed = indexed, running = true)

        var offset = 0
        val pageSize = 500
        val pendingRows = mutableListOf<MessageEmbeddingEntity>()
        while (true) {
            val page = db.messages().pageAll(offset, pageSize)
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
                    vector = packEmbedding(vec),
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

    private fun keyOf(whom: String, id: String) = "$whom:$id"
}
