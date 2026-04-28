// TEMPORARY DUPLICATE of app/src/main/java/io/nisfeb/talon/data/DailyDigestDao.kt
package io.nisfeb.talon.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyDigestDao {

    @Upsert
    suspend fun upsert(d: DailyDigestEntity)

    @Query("SELECT * FROM daily_digests WHERE ship = :ship AND dateLocal = :date LIMIT 1")
    suspend fun get(ship: String, date: String): DailyDigestEntity?

    @Query("SELECT * FROM daily_digests WHERE ship = :ship ORDER BY dateLocal DESC LIMIT 1")
    fun streamLatestForShip(ship: String): Flow<DailyDigestEntity?>

    @Query("DELETE FROM daily_digests WHERE dateLocal < :keepFromDate")
    suspend fun pruneOlderThan(keepFromDate: String)
}
