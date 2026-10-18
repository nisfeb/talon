package io.nisfeb.talon.data

import androidx.compose.runtime.Immutable
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.Flow

/**
 * A loop's call held for the owner: a share_calendar or
 * answer_calendar_offer from a loop with [LoopEntity.askBeforeSharing]
 * on. Nothing is done until Allow ([io.nisfeb.talon.ai.LoopRunner.allow]).
 * Don't drops it, and one left unanswered a day expires. Device-local,
 * like the switch that held it.
 */
@Immutable
@Entity(tableName = "loop_hold")
data class LoopHoldEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val loopId: Long,
    /** The model-facing tool name. */
    val tool: String,
    /** Its arguments as the model gave them, JSON. */
    val args: String,
    /** What the loop wanted, in words. */
    val what: String,
    val heldAt: Long,
)

@Dao
interface LoopHoldDao {
    @Insert
    suspend fun insert(hold: LoopHoldEntity): Long

    /** Held after [since], oldest first: the Loops screen's list. */
    @Query("SELECT * FROM loop_hold WHERE heldAt > :since ORDER BY heldAt, id")
    fun stream(since: Long): Flow<List<LoopHoldEntity>>

    @Query("SELECT * FROM loop_hold WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): LoopHoldEntity?

    /** How many it took: 0 when an earlier tap took it already. */
    @Query("DELETE FROM loop_hold WHERE id = :id")
    suspend fun delete(id: Long): Int

    /** The expired ones, held at or before [before]. */
    @Query("DELETE FROM loop_hold WHERE heldAt <= :before")
    suspend fun deleteBefore(before: Long)
}

internal val LOOP_HOLD_SQL = listOf(
    "ALTER TABLE `loop` ADD COLUMN `askBeforeSharing` INTEGER NOT NULL DEFAULT 0",
    "CREATE TABLE IF NOT EXISTS `loop_hold` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `loopId` INTEGER NOT NULL, " +
        "`tool` TEXT NOT NULL, `args` TEXT NOT NULL, `what` TEXT NOT NULL, `heldAt` INTEGER NOT NULL)",
)

/** 57 to 58: a loop's ask-before-sharing switch, and the calls it holds. Android runs the same statements its own way. */
val LOOP_HOLD_MIGRATION = object : Migration(57, 58) {
    override fun migrate(connection: SQLiteConnection) {
        LOOP_HOLD_SQL.forEach { connection.execSQL(it) }
    }
}
