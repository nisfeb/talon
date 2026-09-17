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
 * What this install holds for one ship's orrery: the key it minted for
 * itself and how far each source has been pushed.
 *
 * The key is a per-ship secret like the session cookie and lives where
 * the app's other per-ship state does. A row exists only while the
 * pipe is on; turning it off revokes the key on the ship and deletes
 * the row.
 *
 * The cursors are conservative on purpose. An observation's id is a
 * hash of its content, so pushing something twice is a no-op on the
 * ship, and a cursor that lags costs a resend, never a duplicate.
 */
@Entity(tableName = "orrery_accounts")
data class OrreryAccountEntity(
    @PrimaryKey val ship: String,
    val clientId: String,
    val token: String,
    /** sentMs of the newest message pushed. */
    val messagesCursor: Long = 0,
    /** `last` of the newest mail thread pushed. */
    val mailCursor: Long = 0,
    /** When the calendar window was last pushed. */
    val calendarCursor: Long = 0,
)

@Dao
interface OrreryAccountDao {
    @Query("SELECT * FROM orrery_accounts WHERE ship = :ship")
    suspend fun get(ship: String): OrreryAccountEntity?

    @Query("SELECT * FROM orrery_accounts WHERE ship = :ship")
    fun stream(ship: String): Flow<OrreryAccountEntity?>

    @Upsert
    suspend fun upsert(row: OrreryAccountEntity)

    @Query("DELETE FROM orrery_accounts WHERE ship = :ship")
    suspend fun delete(ship: String)
}

/** The table exactly as Room creates it, which is what a migration must match. */
internal const val ORRERY_ACCOUNTS_SQL =
    "CREATE TABLE IF NOT EXISTS `orrery_accounts` (`ship` TEXT NOT NULL, `clientId` TEXT NOT NULL, " +
        "`token` TEXT NOT NULL, `messagesCursor` INTEGER NOT NULL, `mailCursor` INTEGER NOT NULL, " +
        "`calendarCursor` INTEGER NOT NULL, PRIMARY KEY(`ship`))"

/** 43 to 44 over a driver connection, for desktop and iOS. Android runs the same statement its own way. */
val ORRERY_ACCOUNTS_MIGRATION = object : Migration(43, 44) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(ORRERY_ACCOUNTS_SQL)
        connection.execSQL(ORRERY_NOTICED_SQL)
        connection.execSQL(ORRERY_CHANNELS_SQL)
    }
}

/**
 * Something the triage noticed in what somebody said: one claim, with
 * where it came from, waiting for the person to confirm or discard it.
 * The snippet is the source text and stays on this device; only the
 * claim and the pointer ever go to the ship.
 */
@Entity(tableName = "orrery_noticed")
data class OrreryNoticedEntity(
    @PrimaryKey val id: String,
    val ship: String,
    val subject: String,
    val attr: String,
    val valueJson: String,
    val atMs: Long,
    val untilMs: Long?,
    val conf: Int,
    val sourceKind: String,
    val sourceId: String,
    /** A body the claim needs that the ship may not have, as JSON, or null. */
    val bodyJson: String?,
    val whom: String,
    val postId: String,
    val snippet: String,
    /** pending, confirmed or discarded. */
    val state: String,
    val createdMs: Long,
)

@Dao
interface OrreryNoticedDao {
    @Query("SELECT * FROM orrery_noticed WHERE ship = :ship AND state = 'pending' ORDER BY createdMs DESC")
    fun pending(ship: String): Flow<List<OrreryNoticedEntity>>

    @Query("SELECT * FROM orrery_noticed WHERE id = :id")
    suspend fun get(id: String): OrreryNoticedEntity?

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun insertIfNew(row: OrreryNoticedEntity): Long

    @Query("UPDATE orrery_noticed SET state = :state WHERE id = :id")
    suspend fun setState(id: String, state: String)

    @Query("SELECT COUNT(*) FROM orrery_noticed WHERE ship = :ship AND attr = :attr AND state = :state")
    suspend fun countByState(ship: String, attr: String, state: String): Int

    @Query("DELETE FROM orrery_noticed WHERE ship = :ship")
    suspend fun clear(ship: String)
}

/** A channel the person lets the triage read. DMs need no row: they are always in scope. */
@Entity(tableName = "orrery_channels")
data class OrreryChannelEntity(@PrimaryKey val whom: String)

@Dao
interface OrreryChannelDao {
    @Query("SELECT whom FROM orrery_channels")
    fun stream(): Flow<List<String>>

    @Query("SELECT whom FROM orrery_channels")
    suspend fun all(): List<String>

    @Upsert
    suspend fun put(row: OrreryChannelEntity)

    @Query("DELETE FROM orrery_channels WHERE whom = :whom")
    suspend fun remove(whom: String)
}

internal const val ORRERY_NOTICED_SQL =
    "CREATE TABLE IF NOT EXISTS `orrery_noticed` (`id` TEXT NOT NULL, `ship` TEXT NOT NULL, `subject` TEXT NOT NULL, " +
        "`attr` TEXT NOT NULL, `valueJson` TEXT NOT NULL, `atMs` INTEGER NOT NULL, `untilMs` INTEGER, `conf` INTEGER NOT NULL, " +
        "`sourceKind` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `bodyJson` TEXT, `whom` TEXT NOT NULL, `postId` TEXT NOT NULL, " +
        "`snippet` TEXT NOT NULL, `state` TEXT NOT NULL, `createdMs` INTEGER NOT NULL, PRIMARY KEY(`id`))"

internal const val ORRERY_CHANNELS_SQL =
    "CREATE TABLE IF NOT EXISTS `orrery_channels` (`whom` TEXT NOT NULL, PRIMARY KEY(`whom`))"
