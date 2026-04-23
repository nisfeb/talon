package io.nisfeb.talon.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface UnreadDao {
    @Upsert
    suspend fun upsert(entity: UnreadEntity)

    @Upsert
    suspend fun upsertAll(entities: List<UnreadEntity>)

    @Query("DELETE FROM unreads WHERE whom = :whom")
    suspend fun delete(whom: String)

    @Query("DELETE FROM unreads")
    suspend fun clear()

    @Query("SELECT * FROM unreads")
    fun stream(): Flow<List<UnreadEntity>>

    @Query("SELECT * FROM unreads WHERE whom = :whom LIMIT 1")
    fun streamFor(whom: String): Flow<UnreadEntity?>
}
