package io.nisfeb.talon.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LoopDao {

    /** Insert or update by primary key; returns the row's id. */
    @Upsert
    suspend fun upsert(loop: LoopEntity): Long

    /** All loops, newest first — the Loops screen list. */
    @Query("SELECT * FROM loop ORDER BY createdAt DESC")
    fun stream(): Flow<List<LoopEntity>>

    /** Loops the scheduler should arm/run. */
    @Query("SELECT * FROM loop WHERE enabled = 1")
    suspend fun enabled(): List<LoopEntity>

    @Query("SELECT * FROM loop WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): LoopEntity?

    /** For applying a synced definition by global id (sync lands later). */
    @Query("SELECT * FROM loop WHERE gid = :gid LIMIT 1")
    suspend fun getByGid(gid: String): LoopEntity?

    @Query("UPDATE loop SET enabled = :enabled, updatedAt = :now WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean, now: Long)

    @Query("UPDATE loop SET lastRunAt = :ranAt WHERE id = :id")
    suspend fun markRan(id: Long, ranAt: Long)

    @Query("DELETE FROM loop WHERE id = :id")
    suspend fun delete(id: Long)

    /** Wipe all loops — used when the ship clears the loops bucket
     *  (del-bucket). Run history is cleared separately via [LoopRunDao]. */
    @Query("DELETE FROM loop")
    suspend fun clearAll()
}
