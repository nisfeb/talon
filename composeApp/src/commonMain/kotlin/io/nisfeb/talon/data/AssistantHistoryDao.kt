package io.nisfeb.talon.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AssistantHistoryDao {

    @Query("SELECT * FROM assistant_history ORDER BY createdAt DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<AssistantHistoryEntity>>

    /** A conversation's turns, oldest-first (chronological reading +
     *  oldest-first context replay to the model). */
    @Query("SELECT * FROM assistant_history WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    suspend fun forConversation(conversationId: Long): List<AssistantHistoryEntity>

    @Insert
    suspend fun insert(row: AssistantHistoryEntity)

    /** Keep only the newest [keep] rows; drop the rest. Run after each
     *  insert so the table stays bounded. */
    @Query(
        "DELETE FROM assistant_history WHERE id NOT IN " +
            "(SELECT id FROM assistant_history ORDER BY createdAt DESC LIMIT :keep)",
    )
    suspend fun trim(keep: Int)

    @Query("DELETE FROM assistant_history")
    suspend fun clearAll()
}
