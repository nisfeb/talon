package io.nisfeb.talon.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * The last listing of each mail folder, kept so a cold start shows the
 * mailbox at once while the ship is asked again.
 *
 * A copy, not a mirror: auspex owns every fact, and the next read
 * replaces a folder's rows whole. Each row is the ship's own JSON for
 * the thread, so a field auspex adds needs no schema change here.
 */
@Entity(tableName = "mail_rows", primaryKeys = ["listing", "threadId"])
data class MailRowEntity(
    /** The folder: a view's wire name, or `label:<name>`. Searches are not kept. */
    val listing: String,
    val threadId: String,
    val ordinal: Int,
    /** The whole folder's size on the ship, the same on every row of it. */
    val total: Int,
    val json: String,
)

@Dao
interface MailRowDao {
    @Query("SELECT * FROM mail_rows WHERE listing = :listing ORDER BY ordinal")
    suspend fun listing(listing: String): List<MailRowEntity>

    @Query("DELETE FROM mail_rows WHERE listing = :listing")
    suspend fun clear(listing: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rows: List<MailRowEntity>)

    /** One folder's rows swapped for the ship's latest answer. */
    @Transaction
    suspend fun replace(listing: String, rows: List<MailRowEntity>) {
        clear(listing)
        if (rows.isNotEmpty()) insert(rows)
    }

    @Query("DELETE FROM mail_rows WHERE threadId = :threadId")
    suspend fun dropThread(threadId: String)
}

/** The table exactly as Room creates it, which is what a migration must match. */
internal const val MAIL_ROWS_SQL =
    "CREATE TABLE IF NOT EXISTS `mail_rows` (`listing` TEXT NOT NULL, `threadId` TEXT NOT NULL, " +
        "`ordinal` INTEGER NOT NULL, `total` INTEGER NOT NULL, `json` TEXT NOT NULL, " +
        "PRIMARY KEY(`listing`, `threadId`))"

/**
 * 41 to 42 over a driver connection, for desktop and iOS. Their first
 * migration: before it, every schema change there wiped the database.
 * Android runs the same statement through its own support-library one.
 */
val MAIL_ROWS_MIGRATION = object : Migration(41, 42) {
    override fun migrate(connection: SQLiteConnection) = connection.execSQL(MAIL_ROWS_SQL)
}
