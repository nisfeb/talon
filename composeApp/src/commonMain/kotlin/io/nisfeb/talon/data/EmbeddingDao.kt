package io.nisfeb.talon.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface EmbeddingDao {
    @Upsert
    suspend fun upsert(row: MessageEmbeddingEntity)

    @Upsert
    suspend fun upsertAll(rows: List<MessageEmbeddingEntity>)

    /** Lookup a single (whom, id) — used to skip re-embedding if textHash matches. */
    @Query("SELECT * FROM message_embeddings WHERE whom = :whom AND id = :id LIMIT 1")
    suspend fun get(whom: String, id: String): MessageEmbeddingEntity?

    /** Every row's "whom:id" and the hash of what it was made from, for
     *  the indexer to tell what is new or changed without a per-row probe. */
    @Query("SELECT whom || ':' || id AS `key`, textHash FROM message_embeddings")
    suspend fun keyHashes(): List<EmbeddingKeyHash>

    /** Drop every vector whose message is deleted or no longer here: search
     *  would find a deleted message by its old text, and one gone takes a
     *  top slot it then has nothing to show for. */
    @Query("""
        DELETE FROM message_embeddings WHERE NOT EXISTS (
            SELECT 1 FROM messages m
            WHERE m.whom = message_embeddings.whom AND m.id = message_embeddings.id AND m.isDeleted = 0
        )
    """)
    suspend fun deleteStale(): Int

    /** Pull every row in batches for the brute-force cosine search, so
     *  a large archive is not read at once: [limit] in key order after
     *  ([whom], [id]), "" and "" first. By the primary key, where an
     *  OFFSET re-read every row before the page (and, unordered, did
     *  not promise the same order twice). */
    @Query("SELECT * FROM message_embeddings WHERE (whom, id) > (:whom, :id) ORDER BY whom, id LIMIT :limit")
    suspend fun pageAfter(whom: String, id: String, limit: Int): List<MessageEmbeddingEntity>

    /** Total embedding rows. UI status. */
    @Query("SELECT COUNT(*) FROM message_embeddings")
    suspend fun count(): Int

    /** Drop a single row: its message was edited down to nothing to embed. */
    @Query("DELETE FROM message_embeddings WHERE whom = :whom AND id = :id")
    suspend fun delete(whom: String, id: String)

    /** All embeddings for a single conversation — used by the topic
     *  cluster panel and "find similar within this chat" lookups. */
    @Query("SELECT * FROM message_embeddings WHERE whom = :whom")
    suspend fun forWhom(whom: String): List<MessageEmbeddingEntity>

    /** Clear everything — used when the user resets the index after a model swap. */
    @Query("DELETE FROM message_embeddings")
    suspend fun clear()
}

/** A row's key ("whom:id") and its [MessageEmbeddingEntity.textHash]. */
data class EmbeddingKeyHash(val key: String, val textHash: Long)
