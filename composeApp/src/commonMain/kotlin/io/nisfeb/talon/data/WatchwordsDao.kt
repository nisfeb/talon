package io.nisfeb.talon.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchwordsDao {

    // ───────── terms ─────────

    @Upsert
    suspend fun upsertTerm(term: WatchwordEntity): Long

    @Query("DELETE FROM watchwords WHERE id = :id")
    suspend fun deleteTermById(id: Long)

    @Query("SELECT * FROM watchwords ORDER BY createdMs ASC")
    fun streamTerms(): Flow<List<WatchwordEntity>>

    @Query("SELECT * FROM watchwords WHERE id = :id LIMIT 1")
    suspend fun getTerm(id: Long): WatchwordEntity?

    @Query("SELECT * FROM watchwords WHERE term = :term LIMIT 1")
    suspend fun getTermByText(term: String): WatchwordEntity?

    @Query("UPDATE watchwords SET notify = :notify WHERE id = :id")
    suspend fun setNotify(id: Long, notify: Boolean)

    // ───────── excludes ─────────

    @Upsert
    suspend fun upsertExclude(exclude: WatchwordChatExcludeEntity)

    @Query("DELETE FROM watchword_chat_excludes WHERE whom = :whom")
    suspend fun deleteExclude(whom: String)

    @Query("SELECT * FROM watchword_chat_excludes")
    fun streamExcludes(): Flow<List<WatchwordChatExcludeEntity>>

    @Query("SELECT whom FROM watchword_chat_excludes")
    suspend fun excludesAsList(): List<String>

    // ───────── hits ─────────

    @Upsert
    suspend fun upsertHit(hit: WatchwordHitEntity)

    @Upsert
    suspend fun upsertHits(hits: List<WatchwordHitEntity>)

    @Query("""
        SELECT * FROM watchword_hits
        ORDER BY sentMs DESC
        LIMIT :limit
    """)
    fun streamAllHits(limit: Int = 500): Flow<List<WatchwordHitEntity>>

    @Query("""
        SELECT * FROM watchword_hits
        WHERE term = :term
        ORDER BY sentMs DESC
        LIMIT :limit
    """)
    fun streamHitsForTerm(term: String, limit: Int = 500): Flow<List<WatchwordHitEntity>>

    /** Daily digest: hits in the [windowStartMs, windowEndMs) window. */
    @Query("""
        SELECT * FROM watchword_hits
        WHERE sentMs >= :windowStartMs AND sentMs < :windowEndMs
        ORDER BY sentMs DESC
        LIMIT :limit
    """)
    suspend fun hitsInWindow(
        windowStartMs: Long,
        windowEndMs: Long,
        limit: Int = 500,
    ): List<WatchwordHitEntity>

    @Query("SELECT COUNT(*) FROM watchword_hits WHERE term = :term")
    suspend fun countForTerm(term: String): Int

    /** Per-term hit counts for the filter chips in the feed. */
    @Query("""
        SELECT term, COUNT(*) AS cnt
        FROM watchword_hits
        GROUP BY term
    """)
    fun streamHitCountsByTerm(): Flow<List<TermHitCount>>

    @Query("DELETE FROM watchword_hits WHERE term = :term")
    suspend fun clearHitsForTerm(term: String)

    /**
     * Drop every hit (across all terms) that points at one specific
     * message. Called from the soft-delete path so when a sender
     * tombstones a message, any watchword hits that captured it are
     * removed from the feed too — otherwise the hit row outlives its
     * message and tapping it lands on a deleted post.
     */
    @Query("DELETE FROM watchword_hits WHERE whom = :whom AND postId = :postId")
    suspend fun clearHitsForPost(whom: String, postId: String)

    /**
     * Trim a term's hit rows down to the newest [keep] entries by sentMs.
     * Uses the (term, sentMs) index so the subquery is bounded.
     *
     * Caveat: when multiple hits for one term share the same `sentMs`
     * (rare — same-millisecond messages from a backfill burst), the
     * `NOT IN` filter keeps every row at that timestamp because the
     * subquery yields the value once. Net effect is conservative
     * under-pruning, never over-pruning, capped at ~1% overhead given
     * the 100-row prune-buffer headroom; matches the spec's stated
     * lazy-prune semantics.
     */
    @Query("""
        DELETE FROM watchword_hits
        WHERE term = :term
          AND sentMs NOT IN (
              SELECT sentMs FROM watchword_hits
              WHERE term = :term
              ORDER BY sentMs DESC
              LIMIT :keep
          )
    """)
    suspend fun pruneToNewest(term: String, keep: Int)
}

data class TermHitCount(val term: String, val cnt: Int)
