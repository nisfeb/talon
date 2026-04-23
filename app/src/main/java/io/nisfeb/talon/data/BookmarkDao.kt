package io.nisfeb.talon.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {

    @Upsert
    suspend fun upsert(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE whom = :whom AND postId = :postId")
    suspend fun remove(whom: String, postId: String)

    /** Emits true while the post is bookmarked. */
    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE whom = :whom AND postId = :postId)")
    fun isBookmarked(whom: String, postId: String): Flow<Boolean>

    @Query("SELECT * FROM bookmarks")
    fun streamAll(): Flow<List<BookmarkEntity>>

    /**
     * Joined view used by the bookmarks screen. Deleted messages are
     * filtered out so tombstones don't show up — if the underlying post
     * goes away, we just stop showing the bookmark (it stays in the
     * table so an un-delete would make it reappear).
     */
    @Query("""
        SELECT b.bookmarkedMs AS bookmarkedMs,
               m.whom AS whom, m.id AS id, m.author AS author,
               m.sentMs AS sentMs, m.contentJson AS contentJson,
               m.kind AS kind, m.isDeleted AS isDeleted, m.parentId AS parentId
        FROM bookmarks b
        INNER JOIN messages m ON m.whom = b.whom AND m.id = b.postId
        WHERE m.isDeleted = 0
        ORDER BY b.bookmarkedMs DESC
    """)
    fun streamBookmarked(): Flow<List<BookmarkedMessage>>
}

data class BookmarkedMessage(
    val bookmarkedMs: Long,
    val whom: String,
    val id: String,
    val author: String,
    val sentMs: Long,
    val contentJson: String,
    val kind: String,
    val isDeleted: Boolean,
    val parentId: String?,
)
