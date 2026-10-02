package io.nisfeb.talon.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import io.nisfeb.talon.urbit.Story
import io.nisfeb.talon.urbit.StoryPart
import kotlinx.serialization.json.Json
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
        upsertRaw(message.normalized().searchable())
    }

    open suspend fun upsertAll(messages: List<MessageEntity>) {
        upsertAllRaw(messages.map { it.normalized().searchable() })
    }

    @Query("SELECT * FROM messages WHERE searchText IS NULL LIMIT :limit")
    protected abstract suspend fun unsearchable(limit: Int): List<MessageEntity>

    @Query("UPDATE messages SET searchText = :text WHERE whom = :whom AND id = :id")
    protected abstract suspend fun setSearchText(whom: String, id: String, text: String)

    /**
     * Give up to [limit] rows stored before [MessageEntity.searchText]
     * existed their text. Returns how many it did; call until 0.
     */
    @Transaction
    open suspend fun fillSearchText(limit: Int): Int {
        val rows = unsearchable(limit)
        rows.forEach { setSearchText(it.whom, it.id, searchTextOf(it.contentJson, it.title)) }
        return rows.size
    }

    @Upsert
    protected abstract suspend fun upsertRaw(message: MessageEntity)

    @Upsert
    protected abstract suspend fun upsertAllRaw(messages: List<MessageEntity>)

    @Query("SELECT * FROM messages WHERE whom = :whom AND id IN (:ids)")
    protected abstract suspend fun getMany(whom: String, ids: List<String>): List<MessageEntity>

    /**
     * [messages] as they would be stored, less those stored exactly so.
     * A page read again (a chat opened, a catch-up) rewrote every row and
     * woke every screen watching the table, for nothing new.
     */
    open suspend fun changedOf(messages: List<MessageEntity>): List<MessageEntity> {
        val incoming = messages.map { it.normalized().searchable() }
        val stored = HashMap<Pair<String, String>, MessageEntity>(incoming.size)
        for ((whom, rows) in incoming.groupBy { it.whom }) {
            for (ids in rows.map { it.id }.chunked(500)) getMany(whom, ids).forEach { stored[it.whom to it.id] = it }
        }
        return incoming.filter { stored[it.whom to it.id] != it }
    }

    /**
     * Rows as [changedOf] gives them, and their media, in one transaction:
     * one commit and one wake for the screens, where each row's media was
     * a transaction of its own.
     */
    @Transaction
    open suspend fun upsertPage(media: MessageMediaDao, messages: List<MessageEntity>) {
        upsertAllRaw(messages)
        for (m in messages) media.replaceForMessage(m.whom, m.id, io.nisfeb.talon.urbit.MediaClassifier.extractMedia(m))
    }

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

    @Query(
        """
        SELECT * FROM messages
        WHERE whom = :whom AND parentId = :parentId AND isDeleted = 0
        ORDER BY sentMs ASC
        """,
    )
    abstract suspend fun repliesSnapshot(whom: String, parentId: String): List<MessageEntity>

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
    @Query("SELECT * FROM messages WHERE whom = :whom AND (id = :da OR id LIKE '%/' || :da) LIMIT 1")
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

    /** Newest N messages (top-level OR replies) for a conversation, newest
     *  first. Used by the Mentions tab's true-mention filter — %activity's
     *  notify-count includes reply mentions, so the scan needs to cover
     *  the thread tree, not just top-level posts. */
    @Query("""
        SELECT * FROM messages
        WHERE whom = :whom AND isDeleted = 0
        ORDER BY sentMs DESC
        LIMIT :count
    """)
    abstract suspend fun latestAnyFor(whom: String, count: Int): List<MessageEntity>

    /** Wall-clock of the newest non-deleted top-level message across a
     *  set of conversations — drives the "active Nm ago" liveness line
     *  on a group's home-list row (its channels as [whoms]). Top-level
     *  only so it agrees with the "Most recent" ordering, which sorts
     *  by [conversationLatest]; counting replies here put a group
     *  labelled "1h ago" below one labelled "12h ago". Null when none
     *  have any message. */
    @Query("""
        SELECT MAX(sentMs) FROM messages
        WHERE whom IN (:whoms) AND isDeleted = 0 AND parentId IS NULL
    """)
    abstract fun streamLatestSentMsAcross(whoms: List<String>): Flow<Long?>

    /** When the newest message kept here was sent; null when none is. */
    @Query("SELECT MAX(sentMs) FROM messages")
    abstract suspend fun newestSentMs(): Long?

    /** Newest non-deleted top-level post id for a conversation (refresh cursor). */
    @Query("""
        SELECT id FROM messages
        WHERE whom = :whom AND isDeleted = 0 AND parentId IS NULL
        ORDER BY sentMs DESC, id DESC LIMIT 1
    """)
    abstract suspend fun newestIdFor(whom: String): String?

    /**
     * The messages just before [beforeMs] in one conversation, newest
     * first, ours among them: a reply reads right only with what it
     * answers. Callers reverse it for the oldest first.
     */
    @Query("""
        SELECT * FROM messages
        WHERE whom = :whom AND isDeleted = 0 AND parentId IS NULL AND sentMs < :beforeMs
        ORDER BY sentMs DESC LIMIT :limit
    """)
    abstract suspend fun before(whom: String, beforeMs: Long, limit: Int): List<MessageEntity>

    /** Other people's posts after [sinceMs] and before [beforeMs], newest first: what a cursor has already walked. */
    @Query("""
        SELECT * FROM messages
        WHERE isDeleted = 0 AND parentId IS NULL AND sentMs > :sinceMs AND sentMs < :beforeMs AND author != :notAuthor
        ORDER BY sentMs DESC LIMIT :limit
    """)
    abstract suspend fun postsBetween(sinceMs: Long, beforeMs: Long, notAuthor: String, limit: Int): List<MessageEntity>

    /**
     * Remove stale optimistic-insert rows for a channel where id still
     * starts with "~". Channel post ids from the ship are raw @ud; any
     * leading-tilde row is a ghost from a pre-fix local send whose
     * echo arrived under a different id. Safe because %channels never
     * assigns author-prefixed ids.
     */
    // Never a queued row: it is waiting for the ship, and this runs on
    // every refresh of the conversation, opening it included.
    @Query("DELETE FROM messages WHERE whom = :whom AND (id LIKE '~%' OR id LIKE 'local_%') AND (status IS NULL OR status != 'queued')")
    abstract suspend fun purgeStaleLocalIds(whom: String)

    /** Our messages waiting for the ship (status "queued"), oldest first, the order they go out in. */
    @Query("SELECT * FROM messages WHERE status = 'queued' ORDER BY sentMs ASC")
    abstract suspend fun queued(): List<MessageEntity>

    /** How many of one conversation's messages are waiting for the ship. */
    @Query("SELECT COUNT(*) FROM messages WHERE whom = :whom AND status = 'queued'")
    abstract suspend fun queuedIn(whom: String): Int

    /** A conversation's messages, all of them: one the ship no longer has. */
    @Query("DELETE FROM messages WHERE whom = :whom")
    abstract suspend fun deleteConversation(whom: String)

    /** Whether anything of a conversation is kept here. */
    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE whom = :whom)")
    abstract suspend fun hasConversation(whom: String): Boolean

    /** How many messages are waiting for the ship. */
    @Query("SELECT COUNT(*) FROM messages WHERE status = 'queued'")
    abstract fun queuedCount(): Flow<Int>

    /**
     * Whether the ship's own copy of our post sent at [sentMs] is here: a
     * row by [author] at that time under an id the ship gave it. A queued
     * channel post that has one got there, whatever the write that timed
     * out said, and is not sent again.
     */
    @Query("SELECT COUNT(*) FROM messages WHERE whom = :whom AND author = :author AND sentMs = :sentMs AND id NOT LIKE 'local_%' AND isDeleted = 0")
    abstract suspend fun shipCopyCount(whom: String, author: String, sentMs: Long): Int

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
    abstract suspend fun reapLocalTwin(whom: String, author: String, sentMs: Long): Int

    /**
     * Fallback reap for our own echo when [reapLocalTwin]'s exact
     * (whom, author, sentMs) match found nothing — e.g. the host didn't
     * round-trip essay.sent byte-for-byte. Removes the single oldest
     * still-pending local twin for this channel. Echoes arrive in
     * send order, so oldest-first is the right one; the caller only
     * invokes this after confirming an exact miss, so a re-delivered
     * echo (whose twin is already gone) can't clobber a different
     * in-flight post.
     */
    @Query("""
        DELETE FROM messages
        WHERE whom = :whom AND id = (
            SELECT id FROM messages
            WHERE whom = :whom AND author = :author AND id LIKE 'local_%'
            ORDER BY sentMs ASC LIMIT 1
        )
    """)
    abstract suspend fun reapOldestLocalTwin(whom: String, author: String): Int

    /**
     * Set the send-state column for one row. The send path marks a
     * row "failed" when the ship refuses it; sent is implicit, by the
     * local twin getting reaped. The id is undotted as [upsert] stores
     * it, or a dotted DM id would match no row.
     */
    open suspend fun setStatus(whom: String, id: String, status: String?) =
        setStatusRaw(whom, id.replace(".", ""), status)

    @Query("UPDATE messages SET status = :status WHERE whom = :whom AND id = :id")
    protected abstract suspend fun setStatusRaw(whom: String, id: String, status: String?)

    /**
     * Per-parent reply digest: count, most-recent reply timestamp, and
     * the author of that most-recent reply. Drives the thread indicator
     * under each top-level message (`"$N replies · author · 2m ago"`
     * with an avatar). `lastAuthor` is the patp of the most-recent
     * replier — pulled via correlated subquery so the row matches the
     * MAX(sentMs)'s author rather than the first one GROUP BY happens
     * to pick. Always populated when count > 0 (rows with zero replies
     * never appear in this query because of the `parentId IS NOT NULL`
     * filter).
     */
    @Query("""
        SELECT m.parentId AS postId,
               COUNT(*) AS count,
               MAX(m.sentMs) AS lastSentMs,
               (SELECT r.author FROM messages r
                WHERE r.whom = :whom
                  AND r.parentId = m.parentId
                  AND r.isDeleted = 0
                ORDER BY r.sentMs DESC, r.id DESC
                LIMIT 1) AS lastAuthor
        FROM messages m
        WHERE m.whom = :whom AND m.parentId IS NOT NULL AND m.isDeleted = 0
        GROUP BY m.parentId
    """)
    abstract fun streamReplyCounts(whom: String): Flow<List<ReplyCount>>

    /**
     * Latest top-level message per conversation — drives the DM list.
     * One row per whom, id the tiebreaker when two posts share a sentMs
     * (scry replies, bulk imports). The subquery runs once a conversation
     * (the distinct whoms come off the index), where it ran once a
     * message, every write. Collect [latestPerConversation], which shares
     * one run among every screen.
     */
    @Query("""
        SELECT m.* FROM (SELECT DISTINCT whom FROM messages) w
        JOIN messages m ON m.rowid = (
            SELECT m2.rowid FROM messages m2
            WHERE m2.whom = w.whom AND m2.parentId IS NULL AND m2.isDeleted = 0
            ORDER BY m2.sentMs DESC, m2.id DESC
            LIMIT 1
        )
        ORDER BY m.sentMs DESC
    """)
    abstract fun conversationLatest(): Flow<List<MessageEntity>>

    /**
     * Substring search across messages' titles and words as shown
     * ([MessageEntity.searchText]). It matched the story JSON once, so
     * "ship", "link" or "break" found every mention, link and line break.
     *
     * Callers MUST pre-escape the needle via [escapeLikeNeedle] — without
     * it, queries containing `%` or `_` produce wrong results (search
     * for "100%" returns every message). The ESCAPE clause below tells
     * SQLite that `\` is a literal-prefix marker.
     */
    @Query("""
        SELECT * FROM messages
        WHERE isDeleted = 0
          AND COALESCE(searchText, contentJson) LIKE '%' || :needle || '%' ESCAPE '\' COLLATE NOCASE
        ORDER BY sentMs DESC
        LIMIT 100
    """)
    abstract fun search(needle: String): Flow<List<MessageEntity>>

    /**
     * Search with the operator-aware shape parsed by
     * [io.nisfeb.talon.ui.parseSearchFilter]. Each filter value is
     * NULL-checked at the SQL level so callers pass NULL for
     * "operator absent" — the conditional plan is the same query,
     * just unfilters the unused legs. Boolean flags use 0/1 for the
     * same reason: SQLite doesn't have a real Boolean.
     *
     * `hasImage` covers Photo / Gif / Video together (the user just
     * wants "messages with an image-shaped attachment"). `hasLink`
     * covers Link.
     *
     * Limit 100 mirrors the existing [search] cap so a worst-case
     * query stays bounded.
     */
    @Query("""
        SELECT * FROM messages m
        WHERE m.isDeleted = 0
          AND (
            :needle IS NULL
            OR COALESCE(m.searchText, m.contentJson) LIKE '%' || :needle || '%' ESCAPE '\' COLLATE NOCASE
          )
          AND (:fromShip IS NULL OR m.author = :fromShip)
          AND (:inWhom IS NULL OR m.whom = :inWhom)
          AND (:sinceMs IS NULL OR m.sentMs >= :sinceMs)
          AND (
            :hasImage = 0
            OR EXISTS (
              SELECT 1 FROM message_media mm
              WHERE mm.whom = m.whom
                AND mm.messageId = m.id
                AND mm.category IN ('Photo', 'Gif', 'Video')
            )
          )
          AND (
            :hasLink = 0
            OR EXISTS (
              SELECT 1 FROM message_media mm
              WHERE mm.whom = m.whom
                AND mm.messageId = m.id
                AND mm.category = 'Link'
            )
          )
        ORDER BY m.sentMs DESC
        LIMIT 100
    """)
    abstract fun searchFiltered(
        needle: String?,
        fromShip: String?,
        inWhom: String?,
        sinceMs: Long?,
        hasImage: Int,
        hasLink: Int,
    ): Flow<List<MessageEntity>>

    /**
     * Backfill candidates for [Watchwords.runBackfill]. The LIKE
     * pre-filter on contentJson narrows candidates without parsing
     * JSON; callers verify each survivor against the rendered plain
     * text in memory. Returns a List so the consumer can iterate and
     * break once the per-term hit cap is reached.
     *
     * Callers MUST pre-escape the term via [escapeLikeNeedle] — same
     * reason as [search] above.
     */
    @Query("""
        SELECT * FROM messages
        WHERE isDeleted = 0
          AND author != :exceptAuthor
          AND COALESCE(searchText, contentJson) LIKE '%' || :term || '%' ESCAPE '\' COLLATE NOCASE
        ORDER BY sentMs DESC
    """)
    abstract suspend fun candidatesForBackfill(term: String, exceptAuthor: String): List<MessageEntity>

}

data class ReplyCount(
    val postId: String,
    val count: Int,
    /** Wall-clock ms of the freshest reply in this thread. 0 when the
     *  query somehow returns a count > 0 with no rows — defensive only;
     *  Room guarantees these line up. */
    val lastSentMs: Long = 0L,
    /** Patp of the author of the freshest reply. Empty when unknown
     *  (same defensive caveat as [lastSentMs]). */
    val lastAuthor: String = "",
)

/**
 * Force id + parentId to their canonical undotted form. The whole app
 * only reads / writes undotted ids; wire payloads carry dotted @ud so
 * any ingest path that forgets to strip dots produces a phantom twin.
 */
internal fun MessageEntity.searchable(): MessageEntity = copy(searchText = searchTextOf(contentJson, title))

/**
 * The title and the words a reader sees, for search. Not the story JSON,
 * and not the placeholders previews use ("[image]" would match "image").
 */
internal fun searchTextOf(contentJson: String, title: String?): String {
    val head = listOfNotNull(title?.trim()?.takeIf { it.isNotEmpty() })
    val parts = runCatching { Story.parse(Json.parseToJsonElement(contentJson)) }.getOrNull()
        ?: return (head + contentJson).joinToString("\n")
    val words = parts.mapNotNull { part ->
        when (part) {
            is StoryPart.Text -> part.text.text
            is StoryPart.Code -> part.code
            is StoryPart.Image -> part.alt
            is StoryPart.Table -> (listOf(part.header) + part.rows).joinToString("\n") { row -> row.joinToString(" ") { it.text } }
            is StoryPart.LinkPreview -> listOfNotNull(part.title, part.url).joinToString(" ")
            is StoryPart.CalWidget -> part.title
            is StoryPart.PollWidget -> (listOf(part.question) + part.options).joinToString("\n")
            else -> null
        }
    }
    return (head + words).joinToString("\n")
}

internal fun MessageEntity.normalized(): MessageEntity {
    val rawId = id
    val rawParent = parentId
    if (!rawId.contains('.') && rawParent?.contains('.') != true) return this
    return copy(
        id = rawId.replace(".", ""),
        parentId = rawParent?.replace(".", ""),
    )
}

/**
 * Escape SQL LIKE wildcards in a user-supplied needle. Pairs with
 * the `ESCAPE '\'` clause on the LIKE queries in [MessageDao].
 * Without this, a search for "100%" matches every message because
 * `%` is a wildcard. Order matters — backslash first, otherwise
 * we double-escape the escapes we're inserting.
 */
fun escapeLikeNeedle(s: String): String =
    s.replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")
