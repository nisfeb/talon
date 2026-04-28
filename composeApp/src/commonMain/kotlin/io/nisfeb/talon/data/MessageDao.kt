// TEMPORARY DUPLICATE of app/src/main/java/io/nisfeb/talon/data/MessageDao.kt
package io.nisfeb.talon.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class MessageDao {
    /**
     * Upsert a single message. Strips dot-grouping from id + parentId
     * before the write so a dotted id can never reach the DB — that
     * bug has burnt us twice (DMs doubled across the full history when
     * SSE and bootstrap stored the same post under two keys). This is
     * the last-line-of-defense guard; all our ingest paths should also
     * normalize, but if one regresses, the DAO still keeps the DB sane.
     */
    open suspend fun upsert(message: MessageEntity) {
        upsertRaw(message.normalized())
    }

    open suspend fun upsertAll(messages: List<MessageEntity>) {
        upsertAllRaw(messages.map { it.normalized() })
    }

    @Upsert
    protected abstract suspend fun upsertRaw(message: MessageEntity)

    @Upsert
    protected abstract suspend fun upsertAllRaw(messages: List<MessageEntity>)

    @Query("UPDATE messages SET isDeleted = 1 WHERE whom = :whom AND id = :id")
    abstract suspend fun softDelete(whom: String, id: String)

    /** Top-level messages in one conversation, oldest first. */
    @Query("""
        SELECT * FROM messages
        WHERE whom = :whom AND isDeleted = 0 AND parentId IS NULL
        ORDER BY sentMs ASC
    """)
    abstract fun stream(whom: String): Flow<List<MessageEntity>>

    /** Replies under a given parent, oldest first. */
    @Query("""
        SELECT * FROM messages
        WHERE whom = :whom AND parentId = :parentId AND isDeleted = 0
        ORDER BY sentMs ASC
    """)
    abstract fun streamReplies(whom: String, parentId: String): Flow<List<MessageEntity>>

    /** One specific message by key. Used to render the thread's parent row. */
    @Query("SELECT * FROM messages WHERE whom = :whom AND id = :id LIMIT 1")
    abstract fun streamOne(whom: String, id: String): Flow<MessageEntity?>

    /** Synchronous-from-suspend lookup. */
    @Query("SELECT * FROM messages WHERE whom = :whom AND id = :id LIMIT 1")
    abstract suspend fun getOne(whom: String, id: String): MessageEntity?

    /**
     * Look up a post by its @da suffix within a conversation. Tlon cite
     * `where` paths reference posts by bare @da (not the full
     * "~author/<da>" key we store), so we suffix-match on id.
     */
    @Query("SELECT * FROM messages WHERE whom = :whom AND id LIKE '%/' || :da LIMIT 1")
    abstract suspend fun findByDa(whom: String, da: String): MessageEntity?

    /** Oldest non-deleted top-level post id for a conversation (pagination cursor). */
    @Query("""
        SELECT id FROM messages
        WHERE whom = :whom AND isDeleted = 0 AND parentId IS NULL
        ORDER BY sentMs ASC LIMIT 1
    """)
    abstract suspend fun oldestIdFor(whom: String): String?

    /** Newest N top-level messages for a conversation, newest first. */
    @Query("""
        SELECT * FROM messages
        WHERE whom = :whom AND isDeleted = 0 AND parentId IS NULL
        ORDER BY sentMs DESC
        LIMIT :count
    """)
    abstract suspend fun latestFor(whom: String, count: Int): List<MessageEntity>

    /** Newest non-deleted top-level post id for a conversation (refresh cursor). */
    @Query("""
        SELECT id FROM messages
        WHERE whom = :whom AND isDeleted = 0 AND parentId IS NULL
        ORDER BY sentMs DESC, id DESC LIMIT 1
    """)
    abstract suspend fun newestIdFor(whom: String): String?

    /**
     * Remove stale optimistic-insert rows for a channel where id still
     * starts with "~". Channel post ids from the ship are raw @ud; any
     * leading-tilde row is a ghost from a pre-fix local send whose
     * echo arrived under a different id. Safe because %channels never
     * assigns author-prefixed ids.
     */
    @Query("DELETE FROM messages WHERE whom = :whom AND (id LIKE '~%' OR id LIKE 'local_%')")
    abstract suspend fun purgeStaleLocalIds(whom: String)

    /**
     * Find every (whom, id) whose id contains a dot. Used by the
     * one-shot dotted-id dedupe migration on startup. Returns the
     * rows themselves so callers can re-insert them under their
     * normalized id.
     */
    @Query("SELECT * FROM messages WHERE id LIKE '%.%'")
    abstract suspend fun findDottedIdRows(): List<MessageEntity>

    /** Hard-delete one specific (whom, id) — used during dedupe. */
    @Query("DELETE FROM messages WHERE whom = :whom AND id = :id")
    abstract suspend fun hardDelete(whom: String, id: String)

    /** Stable pagination across the entire messages table — used by
     *  the embedding indexer's backfill pass. Includes soft-deleted
     *  rows so the indexer can mark them seen and skip on re-runs. */
    @Query("SELECT * FROM messages ORDER BY whom, id LIMIT :limit OFFSET :offset")
    abstract suspend fun pageAll(offset: Int, limit: Int): List<MessageEntity>

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
    abstract suspend fun reapLocalTwin(whom: String, author: String, sentMs: Long)

    /** Reply count per top-level post in this conversation. */
    @Query("""
        SELECT parentId AS postId, COUNT(*) AS count
        FROM messages
        WHERE whom = :whom AND parentId IS NOT NULL AND isDeleted = 0
        GROUP BY parentId
    """)
    abstract fun streamReplyCounts(whom: String): Flow<List<ReplyCount>>

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
    abstract fun conversationLatest(): Flow<List<MessageEntity>>

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
    abstract fun search(needle: String): Flow<List<MessageEntity>>

    /**
     * Backfill candidates for [Watchwords.runBackfill]. The LIKE
     * pre-filter on contentJson narrows candidates without parsing
     * JSON; callers verify each survivor against the rendered plain
     * text in memory. Returns a List so the consumer can iterate and
     * break once the per-term hit cap is reached.
     */
    @Query("""
        SELECT * FROM messages
        WHERE isDeleted = 0
          AND author != :exceptAuthor
          AND contentJson LIKE '%' || :term || '%' COLLATE NOCASE
        ORDER BY sentMs DESC
    """)
    abstract suspend fun candidatesForBackfill(term: String, exceptAuthor: String): List<MessageEntity>

    /**
     * Daily digest: scan recent messages for @-mention detection. Caller
     * does the patp-boundary substring check on `contentJson`-derived
     * plaintext. Bounded by the 24h window so the scan stays small.
     */
    @Query("""
        SELECT * FROM messages
        WHERE sentMs >= :windowStartMs
          AND sentMs < :windowEndMs
          AND author != :ourPatp
          AND isDeleted = 0
        ORDER BY sentMs DESC
        LIMIT :limit
    """)
    abstract suspend fun candidatesForMentionScan(
        ourPatp: String,
        windowStartMs: Long,
        windowEndMs: Long,
        limit: Int,
    ): List<MessageEntity>

    /**
     * Daily digest: newest messages from one chat in the window, excluding
     * self. Used to assemble the unread bucket — caller knows from the
     * `unreads` table how many messages this chat has unread, takes
     * min(count, returnedSize) of these.
     */
    @Query("""
        SELECT * FROM messages
        WHERE whom = :whom
          AND sentMs >= :windowStartMs
          AND sentMs < :windowEndMs
          AND author != :ourPatp
          AND isDeleted = 0
        ORDER BY sentMs DESC
        LIMIT :limit
    """)
    abstract suspend fun newestInChatForWindow(
        whom: String,
        ourPatp: String,
        windowStartMs: Long,
        windowEndMs: Long,
        limit: Int,
    ): List<MessageEntity>
}

data class ReplyCount(val postId: String, val count: Int)

/**
 * Force id + parentId to their canonical undotted form. The whole app
 * only reads / writes undotted ids; wire payloads carry dotted @ud so
 * any ingest path that forgets to strip dots produces a phantom twin.
 */
internal fun MessageEntity.normalized(): MessageEntity {
    val rawId = id
    val rawParent = parentId
    if (!rawId.contains('.') && rawParent?.contains('.') != true) return this
    return copy(
        id = rawId.replace(".", ""),
        parentId = rawParent?.replace(".", ""),
    )
}
