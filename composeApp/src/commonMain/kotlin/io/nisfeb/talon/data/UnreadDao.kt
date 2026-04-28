// TEMPORARY DUPLICATE of app/src/main/java/io/nisfeb/talon/data/UnreadDao.kt
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
