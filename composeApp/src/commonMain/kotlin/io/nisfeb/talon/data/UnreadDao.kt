package io.nisfeb.talon.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface UnreadDao {
    @Upsert
    suspend fun upsert(entity: UnreadEntity)

    @Upsert
    suspend fun upsertAll(entities: List<UnreadEntity>)

    @Query("DELETE FROM unreads WHERE whom = :whom")
    suspend fun delete(whom: String)

    @Query("DELETE FROM unreads")
    suspend fun clear()

    @Query("SELECT * FROM unreads")
    fun stream(): Flow<List<UnreadEntity>>

    /**
     * Mention-only stream — drives the Mentions tab in DmListScreen.
     * Filtered in SQL rather than in Kotlin so we don't ship the full
     * unreads list across the Compose boundary on every change. On a
     * 1000-chat ship the wire shape goes from "every row, every emit"
     * to "only rows that actually have notify content".
     */
    @Query("SELECT * FROM unreads WHERE notifyCount > 0")
    fun streamWithMentions(): Flow<List<UnreadEntity>>

    @Query("SELECT * FROM unreads WHERE whom = :whom LIMIT 1")
    fun streamFor(whom: String): Flow<UnreadEntity?>

    /** One-shot lookup. Used to capture pre-entry unread count before
     *  mark-read races to zero it. */
    @Query("SELECT * FROM unreads WHERE whom = :whom LIMIT 1")
    suspend fun getOne(whom: String): UnreadEntity?

    /** Daily digest: snapshot of every unread row. Caller filters
     *  out muted / watchword-excluded chats before assembling buckets.
     *  Ordered by recency DESC so the digest's "last 24h" framing
     *  surfaces the freshest activity first when the cap is hit. */
    @Query("SELECT * FROM unreads ORDER BY recencyMs DESC")
    suspend fun getAll(): List<UnreadEntity>
}
