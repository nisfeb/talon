package io.nisfeb.talon.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Upsert
    suspend fun upsert(contact: ContactEntity)

    @Upsert
    suspend fun upsertAll(contacts: List<ContactEntity>)

    @Query("SELECT * FROM contacts")
    fun stream(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE ship = :ship LIMIT 1")
    suspend fun get(ship: String): ContactEntity?

    /** All contacts with a non-empty status, newest update first. */
    @Query("""
        SELECT * FROM contacts
        WHERE status IS NOT NULL AND status != ''
        ORDER BY COALESCE(statusUpdatedMs, 0) DESC
    """)
    fun streamStatusFeed(): Flow<List<ContactEntity>>
}
