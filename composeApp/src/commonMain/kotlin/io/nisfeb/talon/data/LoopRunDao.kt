package io.nisfeb.talon.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LoopRunDao {

    @Insert
    suspend fun insert(run: LoopRunEntity): Long

    @Query("SELECT * FROM loop_run WHERE loopId = :loopId ORDER BY ranAt DESC LIMIT :limit")
    fun streamForLoop(loopId: Long, limit: Int): Flow<List<LoopRunEntity>>

    /** Most recent runs across ALL loops, newest first, joined with each
     *  loop's name — backs the assistant's "recent jobs" feed. A run whose
     *  loop was deleted is dropped by the inner join (its history is also
     *  deleted, so it can't appear). */
    @Query(
        "SELECT r.loopId AS loopId, l.name AS loopName, r.ranAt AS ranAt, " +
            "r.ok AS ok, r.output AS output " +
            "FROM loop_run r JOIN loop l ON l.id = r.loopId " +
            "ORDER BY r.ranAt DESC LIMIT :limit",
    )
    fun streamRecent(limit: Int): Flow<List<LoopRunWithName>>

    @Query("DELETE FROM loop_run WHERE loopId = :loopId")
    suspend fun deleteForLoop(loopId: Long)

    /** Wipe all run history — paired with [LoopDao.clearAll] on a
     *  loops-bucket del-bucket. */
    @Query("DELETE FROM loop_run")
    suspend fun clearAll()

    /** Keep only the [keep] most recent runs for a loop. */
    @Query(
        "DELETE FROM loop_run WHERE loopId = :loopId AND id NOT IN " +
            "(SELECT id FROM loop_run WHERE loopId = :loopId ORDER BY ranAt DESC LIMIT :keep)",
    )
    suspend fun pruneForLoop(loopId: Long, keep: Int)
}

/** Projection for the cross-loop recent-runs feed: a run plus its loop's
 *  name. Not an @Entity — Room maps the aliased columns by name. */
data class LoopRunWithName(
    val loopId: Long,
    val loopName: String,
    val ranAt: Long,
    val ok: Boolean,
    val output: String,
)
