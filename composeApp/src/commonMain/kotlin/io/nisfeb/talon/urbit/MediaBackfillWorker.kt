package io.nisfeb.talon.urbit

import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * One-shot scan that walks every existing `messages` row and writes
 * `message_media` rows. Skipped if `message_media` already has rows
 * (idempotent — the only scenario where we want to re-run is a bug
 * fix to [MediaClassifier], in which case the developer manually
 * truncates `message_media` and re-populates).
 *
 * Runs on [Dispatchers.IO] in 1000-message chunks so the UI thread
 * doesn't block. Each chunk commits in its own transaction; Room's
 * flows fire as the chunks land, so the group-info stats grid
 * updates live as the backfill progresses.
 */
object MediaBackfillWorker {

    private const val CHUNK_SIZE = 1000

    /**
     * Launch the backfill on [scope]. No-op if already populated.
     * Safe to call multiple times — second invocation is a no-op
     * after the first completes.
     */
    fun launchIfNeeded(scope: CoroutineScope, db: AppDatabase) {
        scope.launch(Dispatchers.IO) {
            runIfNeeded(db)
        }
    }

    /** Suspend variant for tests + manual triggers. */
    suspend fun runIfNeeded(db: AppDatabase) {
        val mediaDao = db.messageMedia()
        if (mediaDao.totalCount() > 0) {
            Log.i("MediaBackfill", "skip — message_media already populated")
            return
        }
        var offset = 0
        var totalProcessed = 0
        var totalMedia = 0
        while (true) {
            // MessageDao.pageAll uses (offset, limit) parameter order.
            val chunk: List<MessageEntity> = db.messages().pageAll(offset, CHUNK_SIZE)
            if (chunk.isEmpty()) break
            val rows = chunk.flatMap { MediaClassifier.extractMedia(it) }
            if (rows.isNotEmpty()) {
                mediaDao.insertAll(rows)
            }
            totalProcessed += chunk.size
            totalMedia += rows.size
            offset += CHUNK_SIZE
            Log.i("MediaBackfill", "processed=$totalProcessed mediaRows=$totalMedia")
        }
        Log.i("MediaBackfill", "complete — processed=$totalProcessed mediaRows=$totalMedia")
    }
}
