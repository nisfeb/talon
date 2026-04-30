package io.nisfeb.talon.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ReactionUsageDao {

    @Query(
        "SELECT * FROM reaction_usage " +
            "ORDER BY count DESC, lastUsedMs DESC LIMIT :limit"
    )
    fun streamTop(limit: Int): Flow<List<ReactionUsageEntity>>

    @Query("SELECT count FROM reaction_usage WHERE shortcode = :code")
    suspend fun countFor(code: String): Int?

    @Query(
        "INSERT OR REPLACE INTO reaction_usage " +
            "(shortcode, count, lastUsedMs) VALUES (:code, :count, :now)"
    )
    suspend fun upsert(code: String, count: Int, now: Long)

    @Transaction
    suspend fun bump(code: String, now: Long = System.currentTimeMillis()) {
        val current = countFor(code) ?: 0
        upsert(code, current + 1, now)
    }
}
