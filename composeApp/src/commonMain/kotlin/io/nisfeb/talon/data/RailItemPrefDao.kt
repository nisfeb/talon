package io.nisfeb.talon.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface RailItemPrefDao {

    @Query("SELECT * FROM rail_item_prefs")
    fun streamAll(): Flow<List<RailItemPrefEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: RailItemPrefEntity)

    @Query("DELETE FROM rail_item_prefs WHERE itemName = :name")
    suspend fun delete(name: String)

    @Query("DELETE FROM rail_item_prefs")
    suspend fun clearAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<RailItemPrefEntity>)

    /** Bulk replace — used by the [SettingsSync] inbound put-bucket
     *  apply path. Atomic so a partial replacement can't leak. */
    @Transaction
    suspend fun replaceAll(rows: List<RailItemPrefEntity>) {
        clearAll()
        if (rows.isNotEmpty()) insertAll(rows)
    }
}
