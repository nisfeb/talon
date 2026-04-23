package io.nisfeb.talon.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface NotifyPreferenceDao {
    @Upsert
    suspend fun upsert(preference: NotifyPreferenceEntity)

    @Query("DELETE FROM notify_preferences WHERE whom = :whom")
    suspend fun clear(whom: String)

    @Query("SELECT level FROM notify_preferences WHERE whom = :whom LIMIT 1")
    suspend fun levelFor(whom: String): String?

    @Query("SELECT * FROM notify_preferences WHERE whom = :whom LIMIT 1")
    fun stream(whom: String): Flow<NotifyPreferenceEntity?>
}
