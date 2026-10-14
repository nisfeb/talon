package io.nisfeb.talon.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.Flow

/**
 * A non-chat notification, kept for the Activity feed's non-chat half: an
 * Orrery action proposal, a finished loop, an Armillary credit, a group
 * invite, a DM request. These fire a local notification and have no
 * `%activity` entry (chat does), so Activity reads them from here instead.
 * Device-local, never synced. Pruned by both count and age (see [NoticeRepo]).
 *
 * [id] is stable per logical event ("loop:<runId>", "orrery:<actionId>") so a
 * re-record replaces rather than duplicates. [target] is a deep-link tag the
 * Activity row hands to `OpenNoticeRequests` to open the feature screen.
 */
@Entity(tableName = "notice")
data class NoticeEntity(
    @PrimaryKey val id: String,
    /** "assistant" | "account" | "chat". The Activity chip for it. */
    val domain: String,
    /** "orrery-proposal" | "loop-result" | "armillary-credit" | "group-invite" | "dm-request". */
    val kind: String,
    val title: String,
    val body: String,
    val createdMs: Long,
    val read: Boolean = false,
    val target: String,
)

@Dao
interface NoticeDao {
    @Upsert
    suspend fun put(row: NoticeEntity)

    @Query("SELECT * FROM notice ORDER BY createdMs DESC")
    fun stream(): Flow<List<NoticeEntity>>

    @Query("UPDATE notice SET read = 1 WHERE id = :id")
    suspend fun markRead(id: String)

    /** Null domain marks every notice read; a domain marks only that one. */
    @Query("UPDATE notice SET read = 1 WHERE (:domain IS NULL OR domain = :domain)")
    suspend fun markAllRead(domain: String?)

    @Query("SELECT COUNT(*) FROM notice WHERE read = 0")
    fun unreadCount(): Flow<Int>

    /** The domains that currently have a notice, so Activity shows a chip per live one. */
    @Query("SELECT DISTINCT domain FROM notice")
    fun domains(): Flow<List<String>>

    @Query("DELETE FROM notice WHERE createdMs < :before")
    suspend fun pruneOlderThan(before: Long)

    @Query("DELETE FROM notice WHERE id NOT IN (SELECT id FROM notice ORDER BY createdMs DESC LIMIT :keep)")
    suspend fun pruneBeyond(keep: Int)
}

internal const val NOTICE_SQL =
    "CREATE TABLE IF NOT EXISTS `notice` (`id` TEXT NOT NULL, `domain` TEXT NOT NULL, " +
        "`kind` TEXT NOT NULL, `title` TEXT NOT NULL, `body` TEXT NOT NULL, " +
        "`createdMs` INTEGER NOT NULL, `read` INTEGER NOT NULL, `target` TEXT NOT NULL, " +
        "PRIMARY KEY(`id`))"

/** 55 to 56: the non-chat notice table. Android runs the same statement its own way. */
val NOTICE_MIGRATION = object : Migration(55, 56) {
    override fun migrate(connection: SQLiteConnection) = connection.execSQL(NOTICE_SQL)
}
