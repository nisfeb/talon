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
 * The calendar's last answer, kept so a cold start paints the month it
 * had rather than an empty grid.
 *
 * One table for every part of that answer, told apart by [kind]: the
 * window's events, the tasks, the calendars themselves, the tags, the
 * zone. Five tables would say nothing this does not, and the calendar
 * reads them together or not at all.
 *
 * A copy, not a mirror. The ship owns every fact here and the next read
 * replaces a kind's rows whole, so each row holds the ship's own JSON
 * for it and a field the calendar gains needs no schema change.
 */
@Entity(tableName = "calendar_rows", primaryKeys = ["kind", "ordinal"])
data class CalendarCacheEntity(
    /** Which part of the answer: window, tasks, calendars, tags, zone. */
    val kind: String,
    val ordinal: Int,
    val json: String,
)

@Dao
interface CalendarCacheDao {
    @Query("SELECT * FROM calendar_rows WHERE kind = :kind ORDER BY ordinal")
    suspend fun read(kind: String): List<CalendarCacheEntity>

    @Query("DELETE FROM calendar_rows WHERE kind = :kind")
    suspend fun clear(kind: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rows: List<CalendarCacheEntity>)

    /** One kind swapped for the ship's latest answer. */
    @Transaction
    suspend fun replace(kind: String, rows: List<CalendarCacheEntity>) {
        clear(kind)
        if (rows.isNotEmpty()) insert(rows)
    }

    @Query("DELETE FROM calendar_rows")
    suspend fun clearAll()
}

/** The table exactly as Room creates it, which is what a migration must match. */
internal const val CALENDAR_ROWS_SQL =
    "CREATE TABLE IF NOT EXISTS `calendar_rows` (`kind` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, " +
        "`json` TEXT NOT NULL, PRIMARY KEY(`kind`, `ordinal`))"

/**
 * 42 to 43 over a driver connection, for desktop and iOS. Android runs
 * the same statement through its own support-library migration.
 */
val CALENDAR_ROWS_MIGRATION = object : Migration(42, 43) {
    override fun migrate(connection: SQLiteConnection) = connection.execSQL(CALENDAR_ROWS_SQL)
}
