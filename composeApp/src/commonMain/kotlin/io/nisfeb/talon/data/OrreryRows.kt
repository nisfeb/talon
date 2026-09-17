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
    override fun migrate(connection: SQLiteConnection) = connection.execSQL(ORRERY_ACCOUNTS_SQL)
}
