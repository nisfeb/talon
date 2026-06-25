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
