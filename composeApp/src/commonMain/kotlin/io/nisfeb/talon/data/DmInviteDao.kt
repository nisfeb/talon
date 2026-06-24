package io.nisfeb.talon.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DmInviteDao {
    @Upsert
    suspend fun upsertAll(entities: List<DmInviteEntity>)

    @Query("DELETE FROM dm_invites WHERE ship = :ship")
    suspend fun delete(ship: String)

    @Query("DELETE FROM dm_invites")
    suspend fun clear()

    @Query("SELECT * FROM dm_invites ORDER BY receivedMs DESC")
    fun stream(): Flow<List<DmInviteEntity>>

    /** Ships currently pending — used to diff a fresh invite snapshot. */
    @Query("SELECT ship FROM dm_invites")
    suspend fun allShips(): List<String>
}
