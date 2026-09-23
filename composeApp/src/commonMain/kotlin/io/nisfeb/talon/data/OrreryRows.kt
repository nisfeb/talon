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
 * itself and how far its mail has been read.
 *
 * The key is a per-ship secret like the session cookie and lives where
 * the app's other per-ship state does. A row exists only while the
 * pipe is on; turning it off revokes the key on the ship and deletes
 * the row.
 *
 * The cursor is conservative on purpose. An observation's id is a
 * hash of its content, so pushing something twice is a no-op on the
 * ship, and a cursor that lags costs a resend, never a duplicate.
 */
@Entity(tableName = "orrery_accounts")
data class OrreryAccountEntity(
    @PrimaryKey val ship: String,
    val clientId: String,
    val token: String,
    /** `last` of the newest mail thread pushed. */
    val mailCursor: Long = 0,
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

/** The table as 43 to 44 made it; [ORRERY_HANDOFF_MIGRATION] makes it again as it is now. */
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
    /** pending, confirming (confirmed here, not yet on the ship), confirmed or discarded. */
    val state: String,
    val createdMs: Long,
)

@Dao
interface OrreryNoticedDao {
    @Query("SELECT * FROM orrery_noticed WHERE ship = :ship AND state = 'pending' ORDER BY createdMs DESC")
    fun pending(ship: String): Flow<List<OrreryNoticedEntity>>

    @Query("SELECT * FROM orrery_noticed WHERE id = :id")
    suspend fun get(id: String): OrreryNoticedEntity?

    /** Claims confirmed in the tray that no pass has put on the ship yet. */
    @Query("SELECT * FROM orrery_noticed WHERE ship = :ship AND state = 'confirming'")
    suspend fun confirming(ship: String): List<OrreryNoticedEntity>

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun insertIfNew(row: OrreryNoticedEntity): Long

    @Query("UPDATE orrery_noticed SET state = :state WHERE id = :id")
    suspend fun setState(id: String, state: String)

    @Query("SELECT COUNT(*) FROM orrery_noticed WHERE ship = :ship AND attr = :attr AND state = :state")
    suspend fun countByState(ship: String, attr: String, state: String): Int

    /** The words behind the person's latest verdicts of one kind, for the pattern gate. */
    @Query("SELECT snippet FROM orrery_noticed WHERE ship = :ship AND state = :state ORDER BY createdMs DESC LIMIT :limit")
    suspend fun snippets(ship: String, state: String, limit: Int): List<String>

    @Query("DELETE FROM orrery_noticed WHERE ship = :ship")
    suspend fun clear(ship: String)
}

internal const val ORRERY_NOTICED_SQL =
    "CREATE TABLE IF NOT EXISTS `orrery_noticed` (`id` TEXT NOT NULL, `ship` TEXT NOT NULL, `subject` TEXT NOT NULL, " +
        "`attr` TEXT NOT NULL, `valueJson` TEXT NOT NULL, `atMs` INTEGER NOT NULL, `untilMs` INTEGER, `conf` INTEGER NOT NULL, " +
        "`sourceKind` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `bodyJson` TEXT, `whom` TEXT NOT NULL, `postId` TEXT NOT NULL, " +
        "`snippet` TEXT NOT NULL, `state` TEXT NOT NULL, `createdMs` INTEGER NOT NULL, PRIMARY KEY(`id`))"

internal const val ORRERY_CHANNELS_SQL =
    "CREATE TABLE IF NOT EXISTS `orrery_channels` (`whom` TEXT NOT NULL, PRIMARY KEY(`whom`))"

/**
 * What this install has already told the ship, so it never says it
 * twice. The ship dedupes an observation by its content, but a body
 * upsert always makes the body again: without this, every pass
 * recreated whatever a consolidation had just merged away.
 *
 * The key says what kind of thing it is: `mail:<thread>` for a thread
 * read at a given last message, `status:<ship>` for a status line read,
 * `sent:<action>` for a message sent, `brief:<day>` for a day's brief
 * and its tags, `reply:<message>` for a reply to one answered, and a
 * few others for the pass's own bookkeeping.
 */
@Entity(tableName = "orrery_sent", primaryKeys = ["ship", "key"])
data class OrrerySentEntity(
    val ship: String,
    val key: String,
    val value: String,
    val atMs: Long,
)

@Dao
interface OrrerySentDao {
    @Query("SELECT * FROM orrery_sent WHERE ship = :ship AND key = :key")
    suspend fun get(ship: String, key: String): OrrerySentEntity?

    @Query("SELECT * FROM orrery_sent WHERE ship = :ship AND key IN (:keys)")
    suspend fun someOf(ship: String, keys: List<String>): List<OrrerySentEntity>

    /**
     * The rows for these keys, asked in bites. SQLite takes 999 bound
     * values in a statement before Android 12 and the callers ask about
     * every contact or every message of a pass, which on a full book is
     * more than that: the query then throws and the whole pass with it.
     */
    suspend fun some(ship: String, keys: List<String>): List<OrrerySentEntity> =
        if (keys.size <= 900) someOf(ship, keys) else keys.chunked(900).flatMap { someOf(ship, it) }

    /** Every key under one prefix: the occurrences of one event, whatever times they were at. */
    @Query("SELECT * FROM orrery_sent WHERE ship = :ship AND key LIKE :prefix || '%'")
    suspend fun under(ship: String, prefix: String): List<OrrerySentEntity>

    @Query("DELETE FROM orrery_sent WHERE ship = :ship AND key = :key")
    suspend fun forget(ship: String, key: String)

    @Upsert
    suspend fun put(row: OrrerySentEntity)

    @Upsert
    suspend fun putAll(rows: List<OrrerySentEntity>)

    @Query("DELETE FROM orrery_sent WHERE ship = :ship")
    suspend fun clear(ship: String)
}

internal const val ORRERY_SENT_SQL =
    "CREATE TABLE IF NOT EXISTS `orrery_sent` (`ship` TEXT NOT NULL, `key` TEXT NOT NULL, " +
        "`value` TEXT NOT NULL, `atMs` INTEGER NOT NULL, PRIMARY KEY(`ship`, `key`))"

/** 44 to 45: what has already been sent. Android runs the same statement its own way. */
val ORRERY_SENT_MIGRATION = object : Migration(44, 45) {
    override fun migrate(connection: SQLiteConnection) = connection.execSQL(ORRERY_SENT_SQL)
}

/**
 * The ship reads the owner's chats and writes the calendar's events
 * itself now, so what Talon kept to do either goes: the channels its
 * triage read, the records of what it had read and written (`msg:`,
 * `cal:`, `occ:`) and the two cursors. SQLite before 3.35 cannot drop
 * a column, so the accounts table is made again without them.
 */
internal val ORRERY_HANDOFF_SQL = listOf(
    "DROP TABLE IF EXISTS `orrery_channels`",
    "DELETE FROM `orrery_sent` WHERE `key` LIKE 'msg:%' OR `key` LIKE 'cal:%' OR `key` LIKE 'occ:%'",
    "CREATE TABLE `orrery_accounts_new` (`ship` TEXT NOT NULL, `clientId` TEXT NOT NULL, " +
        "`token` TEXT NOT NULL, `mailCursor` INTEGER NOT NULL, PRIMARY KEY(`ship`))",
    "INSERT INTO `orrery_accounts_new` (`ship`, `clientId`, `token`, `mailCursor`) " +
        "SELECT `ship`, `clientId`, `token`, `mailCursor` FROM `orrery_accounts`",
    "DROP TABLE `orrery_accounts`",
    "ALTER TABLE `orrery_accounts_new` RENAME TO `orrery_accounts`",
)

/** 46 to 47: see [ORRERY_HANDOFF_SQL]. Android runs the same statements its own way. */
val ORRERY_HANDOFF_MIGRATION = object : Migration(46, 47) {
    override fun migrate(connection: SQLiteConnection) = ORRERY_HANDOFF_SQL.forEach { connection.execSQL(it) }
}
