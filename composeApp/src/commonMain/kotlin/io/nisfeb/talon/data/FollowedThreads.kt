package io.nisfeb.talon.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.Flow

/**
 * A thread followed or unfollowed. The ship keeps it as Tlon's own
 * per-thread setting (%activity's volume for the thread source: its
 * replies unread and notifying, or neither), so it carries to the
 * owner's other devices and to Tlon's apps; this is that setting kept
 * here, for every screen to ask. A thread with no row is left to the
 * defaults: a DM's thread counts, a channel's does not.
 */
@Entity(tableName = "followed_threads", primaryKeys = ["whom", "parentPostId"])
data class FollowedThreadEntity(
    val whom: String,
    /** The top-level post's id, as MessageEntity.parentId has it. */
    val parentPostId: String,
    /** Followed (replies unread and notifying); false: unfollowed (neither). */
    val follow: Boolean,
    /** False while a change made here has not reached the ship. */
    val sent: Boolean,
    val atMs: Long,
)

@Dao
interface FollowedThreadDao {
    @Upsert
    suspend fun upsert(row: FollowedThreadEntity)

    @Query("SELECT * FROM followed_threads WHERE whom = :whom AND parentPostId = :parentPostId")
    suspend fun get(whom: String, parentPostId: String): FollowedThreadEntity?

    @Query("DELETE FROM followed_threads WHERE whom = :whom AND parentPostId = :parentPostId")
    suspend fun delete(whom: String, parentPostId: String)

    @Query("SELECT * FROM followed_threads")
    suspend fun all(): List<FollowedThreadEntity>

    @Query("SELECT * FROM followed_threads WHERE sent = 0")
    suspend fun unsent(): List<FollowedThreadEntity>

    @Query("SELECT * FROM followed_threads WHERE whom = :whom")
    fun streamForWhom(whom: String): Flow<List<FollowedThreadEntity>>

    /**
     * The threads the owner follows, as the Threads lists show them, with
     * their parent post and newest reply as kept here and the ship's
     * unread count. A DM's thread with unread replies is in too unless
     * unfollowed: a DM's thread counts by default. [whom] keeps one
     * conversation's, so a chat does not read every thread's replies.
     */
    @Query("""
        SELECT t.whom AS whom, t.parentPostId AS parentPostId,
               p.author AS parentAuthor, p.contentJson AS parentContent,
               (SELECT COUNT(*) FROM messages r WHERE r.whom = t.whom AND r.parentId = t.parentPostId AND r.isDeleted = 0) AS replyCount,
               (SELECT r.sentMs FROM messages r WHERE r.whom = t.whom AND r.parentId = t.parentPostId AND r.isDeleted = 0 ORDER BY r.sentMs DESC LIMIT 1) AS lastReplyMs,
               (SELECT r.author FROM messages r WHERE r.whom = t.whom AND r.parentId = t.parentPostId AND r.isDeleted = 0 ORDER BY r.sentMs DESC LIMIT 1) AS lastReplier,
               (SELECT r.id FROM messages r WHERE r.whom = t.whom AND r.parentId = t.parentPostId AND r.isDeleted = 0 ORDER BY r.sentMs DESC LIMIT 1) AS lastReplyId,
               COALESCE(u.count, 0) AS unread,
               COALESCE(u.recencyMs, 0) AS recencyMs
        FROM (
            SELECT whom, parentPostId FROM followed_threads WHERE follow = 1
            UNION
            SELECT u2.whom, u2.parentPostId FROM thread_unreads u2
            WHERE u2.count > 0 AND (u2.whom LIKE '~%' OR u2.whom LIKE '0v%')
              AND NOT EXISTS (SELECT 1 FROM followed_threads f WHERE f.whom = u2.whom AND f.parentPostId = u2.parentPostId)
        ) t
        LEFT JOIN messages p ON p.whom = t.whom AND p.id = t.parentPostId
        LEFT JOIN thread_unreads u ON u.whom = t.whom AND u.parentPostId = t.parentPostId
        WHERE :whom IS NULL OR t.whom = :whom
    """)
    fun streamThreads(whom: String? = null): Flow<List<FollowedThreadRow>>

    /**
     * Whether any thread in [streamThreads] has unread replies, for the
     * menu's dot: from the unread counts alone, so a message written
     * anywhere does not ask it again.
     */
    @Query("""
        SELECT EXISTS(SELECT 1 FROM thread_unreads u WHERE u.count > 0 AND (
            EXISTS(SELECT 1 FROM followed_threads f WHERE f.whom = u.whom AND f.parentPostId = u.parentPostId AND f.follow = 1)
            OR ((u.whom LIKE '~%' OR u.whom LIKE '0v%')
                AND NOT EXISTS(SELECT 1 FROM followed_threads f WHERE f.whom = u.whom AND f.parentPostId = u.parentPostId))
        ))
    """)
    fun streamAnyUnread(): Flow<Boolean>
}

/** A followed thread as the Threads lists show it (see [FollowedThreadDao.streamThreads]). */
data class FollowedThreadRow(
    val whom: String,
    val parentPostId: String,
    /** Null while the parent post is not kept here. */
    val parentAuthor: String?,
    val parentContent: String?,
    val replyCount: Int,
    val lastReplyMs: Long?,
    val lastReplier: String?,
    val lastReplyId: String?,
    /** The ship's count of its unread replies. */
    val unread: Int,
    /** When the ship last saw activity in it; 0 when it has nothing unread to say. */
    val recencyMs: Long = 0,
)

/**
 * Threads with unread replies first, then the most recently active: the
 * newest reply kept here, or the ship's word when a reply is not kept yet.
 */
fun threadsInOrder(rows: List<FollowedThreadRow>): List<FollowedThreadRow> =
    rows.sortedWith(compareByDescending<FollowedThreadRow> { it.unread > 0 }.thenByDescending { maxOf(it.lastReplyMs ?: 0L, it.recencyMs) })

internal const val FOLLOWED_THREADS_SQL =
    "CREATE TABLE IF NOT EXISTS `followed_threads` (`whom` TEXT NOT NULL, `parentPostId` TEXT NOT NULL, `follow` INTEGER NOT NULL, `sent` INTEGER NOT NULL, `atMs` INTEGER NOT NULL, PRIMARY KEY(`whom`, `parentPostId`))"

/** 53 to 54: followed threads kept. Android runs the same statement its own way. */
val FOLLOWED_THREADS_MIGRATION = object : Migration(53, 54) {
    override fun migrate(connection: SQLiteConnection) = connection.execSQL(FOLLOWED_THREADS_SQL)
}
