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
 * What the signed-in ship's Jael said about a comet's dome: the name of
 * the registry that attests it (`gw-btc` for Groundwire), or "" for
 * none. Kept so each comet is asked about once, ever; see
 * [io.nisfeb.talon.ui.CometDomes].
 */
@Entity(tableName = "comet_domes")
data class CometDomeEntity(@PrimaryKey val comet: String, val registry: String)

@Dao
interface CometDomeDao {
    @Query("SELECT * FROM comet_domes WHERE comet = :comet")
    suspend fun get(comet: String): CometDomeEntity?

    @Upsert
    suspend fun put(row: CometDomeEntity)
}

internal const val COMET_DOMES_SQL =
    "CREATE TABLE IF NOT EXISTS `comet_domes` (`comet` TEXT NOT NULL, `registry` TEXT NOT NULL, PRIMARY KEY(`comet`))"

/** 45 to 46: comet domes. Android runs the same statement its own way. */
val COMET_DOMES_MIGRATION = object : Migration(45, 46) {
    override fun migrate(connection: SQLiteConnection) = connection.execSQL(COMET_DOMES_SQL)
}
