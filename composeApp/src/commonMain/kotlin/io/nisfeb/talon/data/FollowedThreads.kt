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

    @Query("SELECT * FROM followed_threads")
    fun streamAll(): Flow<List<FollowedThreadEntity>>

    @Query("SELECT * FROM followed_threads WHERE sent = 0")
    suspend fun unsent(): List<FollowedThreadEntity>
}

internal const val FOLLOWED_THREADS_SQL =
    "CREATE TABLE IF NOT EXISTS `followed_threads` (`whom` TEXT NOT NULL, `parentPostId` TEXT NOT NULL, `follow` INTEGER NOT NULL, `sent` INTEGER NOT NULL, `atMs` INTEGER NOT NULL, PRIMARY KEY(`whom`, `parentPostId`))"

/** 53 to 54: followed threads kept. Android runs the same statement its own way. */
val FOLLOWED_THREADS_MIGRATION = object : Migration(53, 54) {
    override fun migrate(connection: SQLiteConnection) = connection.execSQL(FOLLOWED_THREADS_SQL)
}
