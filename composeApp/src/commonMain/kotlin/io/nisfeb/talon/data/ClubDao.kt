// TEMPORARY DUPLICATE of app/src/main/java/io/nisfeb/talon/data/ClubDao.kt
package io.nisfeb.talon.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ClubDao {
    @Upsert
    suspend fun upsert(club: ClubEntity)

    @Upsert
    suspend fun upsertAll(clubs: List<ClubEntity>)

    @Query("SELECT * FROM clubs")
    fun stream(): Flow<List<ClubEntity>>
}
