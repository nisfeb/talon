package io.nisfeb.talon.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface PinDao {

    @Query("SELECT * FROM pins ORDER BY ordinal ASC, whom ASC")
    fun stream(): Flow<List<PinEntity>>

    @Query("SELECT MAX(ordinal) FROM pins")
    suspend fun maxOrdinal(): Int?

    @Query("INSERT OR REPLACE INTO pins (whom, ordinal) VALUES (:whom, :ordinal)")
    suspend fun upsertRaw(whom: String, ordinal: Int)

    @Query("DELETE FROM pins WHERE whom = :whom")
    suspend fun remove(whom: String)

    @Query("SELECT COUNT(*) FROM pins WHERE whom = :whom")
    suspend fun isPinnedCount(whom: String): Int

    /**
     * Pin a conversation at the bottom of the pinned section.
     * Idempotent — re-pinning an already-pinned conversation is a no-op
     * (keeps existing ordinal).
     */
    @Transaction
    suspend fun pin(whom: String) {
        if (isPinnedCount(whom) > 0) return
        val next = (maxOrdinal() ?: -1) + 1
        upsertRaw(whom, next)
    }

    /**
     * Reorder: rewrite ordinals to match the supplied list. Caller
     * passes the full pinned list in its new order.
     */
    @Transaction
    suspend fun reorder(whoms: List<String>) {
        whoms.forEachIndexed { i, w -> upsertRaw(w, i) }
    }
}
