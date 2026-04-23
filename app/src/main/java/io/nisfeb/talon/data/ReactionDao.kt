package io.nisfeb.talon.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ReactionDao {
    @Upsert
    suspend fun upsert(reaction: ReactionEntity)

    @Upsert
    suspend fun upsertAll(reactions: List<ReactionEntity>)

    @Query("DELETE FROM reactions WHERE whom = :whom AND postId = :postId AND author = :author")
    suspend fun delete(whom: String, postId: String, author: String)

    @Query("DELETE FROM reactions WHERE whom = :whom AND postId = :postId")
    suspend fun clearForPost(whom: String, postId: String)

    /** All reactions for one conversation, cheap to join into messages in-memory. */
    @Query("SELECT * FROM reactions WHERE whom = :whom")
    fun stream(whom: String): Flow<List<ReactionEntity>>
}
