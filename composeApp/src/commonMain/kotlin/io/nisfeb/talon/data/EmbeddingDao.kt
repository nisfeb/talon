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

    /** Every (whom, id) we already have embeddings for. Used by the
     *  initial-pass indexer to skip what's already covered without a
     *  per-row probe. */
    @Query("SELECT whom || ':' || id FROM message_embeddings")
    suspend fun allKeys(): List<String>

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

    /** Drop a single row — fired when its parent message gets hard-deleted. */
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
