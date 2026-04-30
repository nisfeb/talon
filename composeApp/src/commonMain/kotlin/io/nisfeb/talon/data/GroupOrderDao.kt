package io.nisfeb.talon.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupOrderDao {

    @Query("SELECT * FROM group_orders ORDER BY ordinal ASC, flag ASC")
    fun stream(): Flow<List<GroupOrderEntity>>

    @Query("INSERT OR REPLACE INTO group_orders (flag, ordinal) VALUES (:flag, :ordinal)")
    suspend fun upsertRaw(flag: String, ordinal: Int)

    @Query("DELETE FROM group_orders WHERE flag = :flag")
    suspend fun remove(flag: String)

    /**
     * Rewrite ordinals to match the supplied list. Caller passes the
     * full ordered group-flag list.
     */
    @Transaction
    suspend fun reorder(flags: List<String>) {
        flags.forEachIndexed { i, flag -> upsertRaw(flag, i) }
    }

    @Query("DELETE FROM group_orders")
    suspend fun clear()

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<GroupOrderEntity>)

    @Transaction
    suspend fun replaceAll(rows: List<GroupOrderEntity>) {
        clear()
        if (rows.isNotEmpty()) insertAll(rows)
    }
}
