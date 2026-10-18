package io.nisfeb.talon.ai

import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEmbeddingEntity
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.urbit.StoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Fills `message_embeddings` from `messages`, for Android's and desktop's
 * indexers, which differ only in the model behind [embed]. A pass first
 * drops the vector of every message deleted or gone, then embeds each
 * message that has no vector yet or has changed since its vector was
 * made ([needsEmbedding]). Nothing else costs a model call.
 *
 * One pass at a time: [start] while one runs does nothing. The shells
 * start a pass when the ship opens with smart features on, when the
 * switch goes on, and when Search is used in smart mode; that last one
 * is how messages that came in since reach the index.
 */
open class MessageIndexer(
    private val db: AppDatabase,
    private val embed: suspend (String) -> FloatArray?,
    /** Runs the pass; an IO scope, which both platforms hand in. */
    private val scope: CoroutineScope,
) {
    private val _progress = MutableStateFlow(IndexProgress())
    val progress: StateFlow<IndexProgress> = _progress.asStateFlow()

    @kotlin.concurrent.Volatile private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { pass() }
    }

    fun stop() { job?.cancel() }

    /** Stop, and wait until it has: the ship's database closes next. */
    suspend fun stopAndJoin() { job?.cancelAndJoin() }

    private suspend fun pass() {
        val dao = db.embeddings()
        dao.deleteStale()
        val stored = dao.keyHashes().associateTo(HashMap()) { it.key to it.textHash }
        var total = stored.size
        var indexed = stored.size
        _progress.value = IndexProgress(running = true, indexed = indexed, total = total)
        val pending = mutableListOf<MessageEmbeddingEntity>()
        try {
            // Paged by key, so a 50K-row archive is never read at once.
            var after = "" to ""
            while (true) {
                val page = db.messages().pageAfter(after.first, after.second, PAGE)
                if (page.isEmpty()) break
                after = page.last().whom to page.last().id
                total += page.count { !it.isDeleted && "${it.whom}:${it.id}" !in stored }
                for (m in page) {
                    yield()
                    val had = stored["${m.whom}:${m.id}"]
                    if (!needsEmbedding(m, had)) continue
                    val text = StoryCache.previewFor(m).trim().take(1000)
                    if (text.length < 4) {
                        // Edited down to nothing to embed: the old vector
                        // would go on finding it by what it used to say.
                        if (had != null) { dao.delete(m.whom, m.id); indexed-- }
                        continue
                    }
                    val hash = embedHash(m)
                    // ponytail: rows made before 1.8.30 carry the hash of
                    // their text, not of their content; one whose text is
                    // unchanged is restamped, not embedded again. Drop this
                    // once every install has run a pass on a later version.
                    val row = if (had == text.hashCode().toLong()) dao.get(m.whom, m.id)?.copy(textHash = hash)
                    else embed(text)?.let { MessageEmbeddingEntity(m.whom, m.id, packEmbedding(it), it.size, hash) }
                    if (row == null) continue
                    pending += row
                    if (had == null) indexed++
                    if (pending.size >= 50) {
                        dao.upsertAll(pending)
                        pending.clear()
                        _progress.value = IndexProgress(running = true, indexed = indexed, total = total)
                    }
                }
                if (page.size < PAGE) break
            }
            if (pending.isNotEmpty()) dao.upsertAll(pending)
        } finally {
            // Also when stopped midway, or the status line says
            // "Indexing" for the rest of the session.
            _progress.value = IndexProgress(running = false, indexed = indexed, total = total)
        }
    }

    private companion object {
        const val PAGE = 500
    }
}

/** What a message's vector is made from, hashed: its content and its
 *  title (a notebook post's), so an edit to either changes it. Read off
 *  the row, without parsing the content, for every message each pass. */
internal fun embedHash(m: MessageEntity): Long =
    (m.title.orEmpty() + "\n" + m.contentJson).hashCode().toLong()

/** Whether a pass embeds [m], given the hash stored with its vector
 *  ([had], null when it has none): a deleted message never, any other
 *  when it is new or has changed since. */
internal fun needsEmbedding(m: MessageEntity, had: Long?): Boolean =
    !m.isDeleted && had != embedHash(m)
