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

    @Query("SELECT * FROM contacts WHERE ship = :ship LIMIT 1")
    fun streamOne(ship: String): Flow<ContactEntity?>

    /** All contacts with a non-empty status, newest update first. */
    @Query("""
        SELECT * FROM contacts
        WHERE status IS NOT NULL AND status != ''
        ORDER BY COALESCE(statusUpdatedMs, 0) DESC
    """)
    fun streamStatusFeed(): Flow<List<ContactEntity>>

    /**
     * People-search: match ship patp or nickname substring, case
     * insensitive. Ordered by nickname-match first, then patp.
     *
     * Callers MUST pre-escape via [escapeLikeNeedle] so `%`, `_`, `\`
     * in user input don't act as wildcards.
     */
    @Query("""
        SELECT * FROM contacts
        WHERE ship LIKE '%' || :q || '%' ESCAPE '\' COLLATE NOCASE
           OR (nickname IS NOT NULL AND nickname LIKE '%' || :q || '%' ESCAPE '\' COLLATE NOCASE)
        ORDER BY
          CASE WHEN nickname LIKE '%' || :q || '%' ESCAPE '\' COLLATE NOCASE THEN 0 ELSE 1 END,
          nickname COLLATE NOCASE,
          ship
        LIMIT 30
    """)
    fun search(q: String): Flow<List<ContactEntity>>
}
