package io.nisfeb.talon.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * A %settings write the ship has not taken yet: made before the channel
 * was attached, while the ship was out of reach, or cut off midway.
 * [payload] is the settings-event as it goes on the wire. One row per
 * [slot] (`bucket` or `bucket/entry`): a newer write there replaces the
 * row and takes a new [id], so the newest goes last. SettingsSyncImpl
 * sends them at the next connect and holds them over the ship's word
 * until the ship has them. Per ship, as the database is.
 */
@Entity(tableName = "settings_outbox", indices = [Index(value = ["slot"], unique = true)])
data class SettingsOutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val slot: String,
    val payload: String,
)

@Dao
interface SettingsOutboxDao {
    /** Kept, replacing what waited in the same slot; its id. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun keep(row: SettingsOutboxEntity): Long

    /** What waits, oldest first: the order it goes in. */
    @Query("SELECT * FROM settings_outbox ORDER BY id")
    suspend fun all(): List<SettingsOutboxEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM settings_outbox WHERE id = :id)")
    suspend fun has(id: Long): Boolean

    /** Taken by the ship, or refused by it: nothing more to send. */
    @Query("DELETE FROM settings_outbox WHERE id = :id")
    suspend fun done(id: Long)
}

/**
 * One number this device keeps for itself, by name: when every chat was
 * last read whole ([POSTS_READ]), and whether the dotted-id cleanup ran
 * ([DOTTED_IDS_DEDUPED]). Never synced.
 */
@Entity(tableName = "marks")
data class MarkEntity(
    @PrimaryKey val name: String,
    val value: Long,
)

@Dao
interface MarkDao {
    @Upsert
    suspend fun put(mark: MarkEntity)

    @Query("SELECT value FROM marks WHERE name = :name")
    suspend fun get(name: String): Long?

    companion object {
        const val POSTS_READ = "posts-read"
        const val DOTTED_IDS_DEDUPED = "dotted-ids-deduped"
    }
}

internal val SYNC_STATE_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS `settings_outbox` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "`slot` TEXT NOT NULL, `payload` TEXT NOT NULL)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_settings_outbox_slot` ON `settings_outbox` (`slot`)",
    "CREATE TABLE IF NOT EXISTS `marks` (`name` TEXT NOT NULL, `value` INTEGER NOT NULL, PRIMARY KEY(`name`))",
)

/** 56 to 57: the settings outbox and the marks. Android runs the same statements its own way. */
val SYNC_STATE_MIGRATION = object : Migration(56, 57) {
    override fun migrate(connection: SQLiteConnection) {
        SYNC_STATE_SQL.forEach { connection.execSQL(it) }
    }
}
