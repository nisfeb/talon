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

    /** Set of every chat the user has muted (level = "none"). Used by
     *  Watchwords to exclude muted chats from both notifications and
     *  the hits feed. */
    @Query("SELECT whom FROM notify_preferences WHERE level = 'none'")
    suspend fun mutedWhoms(): List<String>

    @Query("SELECT * FROM notify_preferences WHERE whom = :whom LIMIT 1")
    fun stream(whom: String): Flow<NotifyPreferenceEntity?>

    @Query("SELECT whom FROM notify_preferences WHERE level = 'none'")
    fun streamMutedWhoms(): Flow<List<String>>

    @Query("DELETE FROM notify_preferences")
    suspend fun clearAll()

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<NotifyPreferenceEntity>)

    @androidx.room.Transaction
    suspend fun replaceAll(rows: List<NotifyPreferenceEntity>) {
        clearAll()
        if (rows.isNotEmpty()) insertAll(rows)
    }
}
