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
 * The agent key this install minted for itself in one ship's lattice:
 * the assistants' memory, as Claude Code's lattice plugin has it. A
 * per-ship secret like orrery's key, kept beside it. [revoked] holds a
 * key lattice refused: the owner revoked it, and minting another of our
 * own accord would undo that, so only "Make a new key" does.
 */
@Entity(tableName = "lattice_keys")
data class LatticeKeyEntity(
    @PrimaryKey val ship: String,
    val keyId: String,
    val token: String,
    val revoked: Boolean = false,
)

@Dao
interface LatticeKeyDao {
    @Query("SELECT * FROM lattice_keys WHERE ship = :ship")
    suspend fun get(ship: String): LatticeKeyEntity?

    @Query("SELECT * FROM lattice_keys WHERE ship = :ship")
    fun stream(ship: String): Flow<LatticeKeyEntity?>

    @Upsert
    suspend fun upsert(row: LatticeKeyEntity)

    @Query("DELETE FROM lattice_keys WHERE ship = :ship")
    suspend fun delete(ship: String)
}

internal const val LATTICE_KEYS_SQL =
    "CREATE TABLE IF NOT EXISTS `lattice_keys` (`ship` TEXT NOT NULL, `keyId` TEXT NOT NULL, " +
        "`token` TEXT NOT NULL, `revoked` INTEGER NOT NULL, PRIMARY KEY(`ship`))"

/** 58 to 59: the lattice key. Android runs the same statement its own way. */
val LATTICE_KEYS_MIGRATION = object : Migration(58, 59) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(LATTICE_KEYS_SQL)
    }
}
