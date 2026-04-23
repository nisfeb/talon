package io.nisfeb.talon.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Upsert
    suspend fun upsert(message: MessageEntity)

    @Upsert
    suspend fun upsertAll(messages: List<MessageEntity>)

    @Query("UPDATE messages SET isDeleted = 1 WHERE whom = :whom AND id = :id")
    suspend fun softDelete(whom: String, id: String)

    /** Top-level messages in one conversation, oldest first. */
    @Query("""
        SELECT * FROM messages
        WHERE whom = :whom AND isDeleted = 0 AND parentId IS NULL
        ORDER BY sentMs ASC
    """)
    fun stream(whom: String): Flow<List<MessageEntity>>

    /** Replies under a given parent, oldest first. */
    @Query("""
        SELECT * FROM messages
        WHERE whom = :whom AND parentId = :parentId AND isDeleted = 0
        ORDER BY sentMs ASC
    """)
    fun streamReplies(whom: String, parentId: String): Flow<List<MessageEntity>>

    /** One specific message by key. Used to render the thread's parent row. */
    @Query("SELECT * FROM messages WHERE whom = :whom AND id = :id LIMIT 1")
    fun streamOne(whom: String, id: String): Flow<MessageEntity?>

    /** Synchronous-from-suspend lookup. */
    @Query("SELECT * FROM messages WHERE whom = :whom AND id = :id LIMIT 1")
    suspend fun getOne(whom: String, id: String): MessageEntity?

    /**
     * Look up a post by its @da suffix within a conversation. Tlon cite
     * `where` paths reference posts by bare @da (not the full
     * "~author/<da>" key we store), so we suffix-match on id.
     */
    @Query("SELECT * FROM messages WHERE whom = :whom AND id LIKE '%/' || :da LIMIT 1")
    suspend fun findByDa(whom: String, da: String): MessageEntity?

    /** Oldest non-deleted top-level post id for a conversation (pagination cursor). */
    @Query("""
        SELECT id FROM messages
        WHERE whom = :whom AND isDeleted = 0 AND parentId IS NULL
        ORDER BY sentMs ASC LIMIT 1
    """)
    suspend fun oldestIdFor(whom: String): String?

    /** Newest non-deleted top-level post id for a conversation (refresh cursor). */
    @Query("""
        SELECT id FROM messages
        WHERE whom = :whom AND isDeleted = 0 AND parentId IS NULL
        ORDER BY sentMs DESC, id DESC LIMIT 1
    """)
    suspend fun newestIdFor(whom: String): String?

    /** Count all rows for a conversation, ignoring filters. Debug only. */
    @Query("SELECT COUNT(*) FROM messages WHERE whom = :whom")
    suspend fun countFor(whom: String): Int

    /**
     * Remove stale optimistic-insert rows for a channel where id still
     * starts with "~". Channel post ids from the ship are raw @ud; any
     * leading-tilde row is a ghost from a pre-fix local send whose
     * echo arrived under a different id. Safe because %channels never
     * assigns author-prefixed ids.
     */
    @Query("DELETE FROM messages WHERE whom = :whom AND (id LIKE '~%' OR id LIKE 'local_%')")
    suspend fun purgeStaleLocalIds(whom: String)

    /**
     * Reap our own `local_*` optimistic-insert twin for a post that the
     * server just echoed back under its own id. Scoped to (whom,
     * author, sentMs) so it only targets the exact matching twin.
     */
    @Query("""
        DELETE FROM messages
        WHERE whom = :whom
          AND author = :author
          AND sentMs = :sentMs
          AND id LIKE 'local_%'
    """)
    suspend fun reapLocalTwin(whom: String, author: String, sentMs: Long)

    /** Count visible (top-level, not deleted) rows for a conversation. Debug only. */
    @Query("SELECT COUNT(*) FROM messages WHERE whom = :whom AND isDeleted = 0 AND parentId IS NULL")
    suspend fun countVisibleFor(whom: String): Int

    /** Find all rows by author in a conversation. Debug only. */
    @Query("""
        SELECT id, author, sentMs, parentId, isDeleted, SUBSTR(contentJson, 1, 120) AS contentPreview
        FROM messages
        WHERE whom = :whom AND author = :author
        ORDER BY sentMs DESC
    """)
    suspend fun debugByAuthor(whom: String, author: String): List<DebugRow>

    /** Find rows whose contentJson contains a substring. Debug only. */
    @Query("""
        SELECT id, author, sentMs, parentId, isDeleted, SUBSTR(contentJson, 1, 500) AS contentPreview
        FROM messages
        WHERE whom = :whom AND contentJson LIKE '%' || :needle || '%'
        ORDER BY sentMs DESC LIMIT 10
    """)
    suspend fun debugSearch(whom: String, needle: String): List<DebugRow>

    data class DebugRow(
        val id: String,
        val author: String,
        val sentMs: Long,
        val parentId: String?,
        val isDeleted: Boolean,
        val contentPreview: String,
    )

    /** Reply count per top-level post in this conversation. */
    @Query("""
        SELECT parentId AS postId, COUNT(*) AS count
        FROM messages
        WHERE whom = :whom AND parentId IS NOT NULL AND isDeleted = 0
        GROUP BY parentId
    """)
    fun streamReplyCounts(whom: String): Flow<List<ReplyCount>>

    /**
     * Latest top-level message per conversation — drives the DM list.
     * Correlated-subquery form picks exactly one row per whom using id
     * as a tiebreaker when two posts share the same sentMs (which
     * happens more often than you'd think — scry replies, bulk imports).
     */
    @Query("""
        SELECT m.*
        FROM messages m
        WHERE m.isDeleted = 0
          AND m.parentId IS NULL
          AND m.id = (
              SELECT m2.id FROM messages m2
              WHERE m2.whom = m.whom
                AND m2.isDeleted = 0
                AND m2.parentId IS NULL
              ORDER BY m2.sentMs DESC, m2.id DESC
              LIMIT 1
          )
        ORDER BY m.sentMs DESC
    """)
    fun conversationLatest(): Flow<List<MessageEntity>>

    /**
     * Substring search across all messages' content JSON. v1 matches raw
     * JSON text — inline text spans come through directly; structural
     * JSON keys (like "inline", "block") would also match but don't come
     * up as realistic queries.
     */
    @Query("""
        SELECT * FROM messages
        WHERE isDeleted = 0
          AND contentJson LIKE '%' || :needle || '%' COLLATE NOCASE
        ORDER BY sentMs DESC
        LIMIT 100
    """)
    fun search(needle: String): Flow<List<MessageEntity>>
}

data class ReplyCount(val postId: String, val count: Int)
