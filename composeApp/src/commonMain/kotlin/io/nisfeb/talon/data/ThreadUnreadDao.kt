package io.nisfeb.talon.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ThreadUnreadDao {
    @Upsert
    suspend fun upsert(entity: ThreadUnreadEntity)

    @Upsert
    suspend fun upsertAll(entities: List<ThreadUnreadEntity>)

    /** Per-conversation stream — drives the per-row thread indicator
     *  tint and the in-thread "New" divider. Empty list when no
     *  threads in this conversation have unread events. */
    @Query("SELECT * FROM thread_unreads WHERE whom = :whom")
    fun streamForWhom(whom: String): Flow<List<ThreadUnreadEntity>>

    /** One-shot lookup for a single thread. Used to compute the
     *  unread-divider anchor at thread-open without subscribing for
     *  the lifetime of the screen. */
    @Query("SELECT * FROM thread_unreads WHERE whom = :whom AND parentPostId = :parentPostId LIMIT 1")
    suspend fun getOne(whom: String, parentPostId: String): ThreadUnreadEntity?

    /** Clear per-thread state for a conversation. Called from
     *  `markRead(whom)` so the channel-level read mirror also clears
     *  the per-thread rows locally (the server's read-action recurses
     *  via `deep=true`, so the next %activity update also reflects
     *  this — but local UI shouldn't lag on the round-trip). */
    @Query("DELETE FROM thread_unreads WHERE whom = :whom")
    suspend fun deleteForWhom(whom: String)

    /** Clear a single thread's unread row — fired when the user opens
     *  the thread, so the row's tint and any in-thread divider clear
     *  immediately. */
    @Query("DELETE FROM thread_unreads WHERE whom = :whom AND parentPostId = :parentPostId")
    suspend fun deleteOne(whom: String, parentPostId: String)

    @Query("DELETE FROM thread_unreads")
    suspend fun clear()
}
