package io.nisfeb.talon.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AssistantConversationDao {

    @Query("SELECT * FROM assistant_conversation ORDER BY updatedAt DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<AssistantConversationEntity>>

    @Query("SELECT * FROM assistant_conversation WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): AssistantConversationEntity?

    /** Resolve a synced conversation to its local row (sync upsert key). */
    @Query("SELECT * FROM assistant_conversation WHERE gid = :gid LIMIT 1")
    suspend fun getByGid(gid: String): AssistantConversationEntity?

    @Query("DELETE FROM assistant_conversation WHERE gid = :gid")
    suspend fun deleteByGid(gid: String)

    /** Newest conversation, used to resume the active topic on reopen. */
    @Query("SELECT * FROM assistant_conversation ORDER BY updatedAt DESC LIMIT 1")
    suspend fun mostRecent(): AssistantConversationEntity?

    @Insert
    suspend fun insert(row: AssistantConversationEntity): Long

    @Update
    suspend fun update(row: AssistantConversationEntity)

    /** Keep only the newest [keep] conversations; their turns are pruned
     *  separately by [AssistantHistoryDao.trim]. */
    @Query(
        "DELETE FROM assistant_conversation WHERE id NOT IN " +
            "(SELECT id FROM assistant_conversation ORDER BY updatedAt DESC LIMIT :keep)",
    )
    suspend fun trim(keep: Int)

    @Query("DELETE FROM assistant_conversation")
    suspend fun clearAll()
}
