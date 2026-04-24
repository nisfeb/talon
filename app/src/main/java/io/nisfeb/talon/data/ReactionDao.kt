package io.nisfeb.talon.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ReactionDao {
    /**
     * Upsert a reaction. Strips dot-grouping from postId — same
     * defense-in-depth as MessageDao.upsert: if an ingest path
     * forgets to normalize, the DAO still keeps dots out of the DB.
     */
    open suspend fun upsert(reaction: ReactionEntity) {
        upsertRaw(reaction.normalized())
    }

    open suspend fun upsertAll(reactions: List<ReactionEntity>) {
        upsertAllRaw(reactions.map { it.normalized() })
    }

    @Upsert
    protected abstract suspend fun upsertRaw(reaction: ReactionEntity)

    @Upsert
    protected abstract suspend fun upsertAllRaw(reactions: List<ReactionEntity>)

    @Query("DELETE FROM reactions WHERE whom = :whom AND postId = :postId AND author = :author")
    abstract suspend fun delete(whom: String, postId: String, author: String)

    @Query("DELETE FROM reactions WHERE whom = :whom AND postId = :postId")
    abstract suspend fun clearForPost(whom: String, postId: String)

    /** All reactions for one conversation, cheap to join into messages in-memory. */
    @Query("SELECT * FROM reactions WHERE whom = :whom")
    abstract fun stream(whom: String): Flow<List<ReactionEntity>>

    @Query("SELECT * FROM reactions WHERE postId LIKE '%.%'")
    abstract suspend fun findDottedPostIdRows(): List<ReactionEntity>

    @Query("""
        DELETE FROM reactions
        WHERE whom = :whom AND postId = :postId AND author = :author
    """)
    abstract suspend fun deleteOne(whom: String, postId: String, author: String)
}

/** Strip dots from postId so dedupe-by-(whom, postId, author) works reliably. */
internal fun ReactionEntity.normalized(): ReactionEntity {
    if (!postId.contains('.')) return this
    return copy(postId = postId.replace(".", ""))
}
