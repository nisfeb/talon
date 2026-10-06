package io.nisfeb.talon.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * What orrery last answered for the Orrery section, kept so the section
 * opens on it at once, after a restart too, while the ship is asked
 * again ([io.nisfeb.talon.orrery.OrreryViewStore]).
 */
@Entity(tableName = "orrery_cache")
data class OrreryCacheEntity(
    /** "state" (GET /api/state) or "plan" (GET /api/travel/last). */
    @PrimaryKey val kind: String,
    val json: String,
    val atMs: Long,
)

@Dao
interface OrreryCacheDao {
    @Query("SELECT * FROM orrery_cache WHERE kind = :kind")
    suspend fun get(kind: String): OrreryCacheEntity?

    @Upsert
    suspend fun put(row: OrreryCacheEntity)
}

internal const val ORRERY_CACHE_SQL =
    "CREATE TABLE IF NOT EXISTS `orrery_cache` (`kind` TEXT NOT NULL, `json` TEXT NOT NULL, `atMs` INTEGER NOT NULL, PRIMARY KEY(`kind`))"

/** 54 to 55: orrery's last answer kept. Android runs the same statement its own way. */
val ORRERY_CACHE_MIGRATION = object : Migration(54, 55) {
    override fun migrate(connection: SQLiteConnection) = connection.execSQL(ORRERY_CACHE_SQL)
}
