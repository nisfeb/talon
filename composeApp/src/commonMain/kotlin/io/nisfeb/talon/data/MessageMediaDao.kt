package io.nisfeb.talon.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Reads + writes for the derived media index.
 *
 * - [streamCounts] backs the group-info stats grid.
 * - [streamCategory] backs the drilldown LazyColumn.
 * - [replaceForMessage] is the single mutator path; called from
 *   [PostIngest] on insert/edit and from the backfill worker.
 */
@Dao
interface MessageMediaDao {

    @Query("""SELECT category, COUNT(*) AS n
              FROM message_media WHERE whom = :whom
              GROUP BY category""")
    fun streamCounts(whom: String): Flow<List<CategoryCount>>

    @Query("""SELECT * FROM message_media
              WHERE whom = :whom AND category = :category
              ORDER BY sentMs DESC LIMIT :limit OFFSET :offset""")
    fun streamCategory(
        whom: String,
        category: String,
        limit: Int,
        offset: Int,
    ): Flow<List<MessageMediaEntity>>

    @Query("DELETE FROM message_media WHERE whom = :whom AND messageId = :id")
    suspend fun deleteForMessage(whom: String, id: String)

    /**
     * Wipe media rows for local optimistic-twin messages keyed on
     * (whom, author, sentMs). Mirrors `MessageDao.reapLocalTwin` so
     * the derived index doesn't accumulate orphan rows for messages
     * we've replaced with their server-echoed versions.
     */
    @Query("""
        DELETE FROM message_media
        WHERE whom = :whom
          AND author = :author
          AND sentMs = :sentMs
          AND messageId LIKE 'local_%'
    """)
    suspend fun reapLocalTwinMedia(whom: String, author: String, sentMs: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<MessageMediaEntity>)

    @Transaction
    suspend fun replaceForMessage(
        whom: String,
        messageId: String,
        rows: List<MessageMediaEntity>,
    ) {
        deleteForMessage(whom, messageId)
        if (rows.isNotEmpty()) insertAll(rows)
    }

    @Query("SELECT COUNT(*) FROM message_media")
    suspend fun totalCount(): Int
}

data class CategoryCount(val category: String, val n: Int)
