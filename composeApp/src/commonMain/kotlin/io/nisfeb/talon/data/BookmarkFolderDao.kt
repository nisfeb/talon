// TEMPORARY DUPLICATE of app/src/main/java/io/nisfeb/talon/data/BookmarkFolderDao.kt
package io.nisfeb.talon.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkFolderDao {

    @Insert
    suspend fun createFolder(folder: BookmarkFolderEntity): Long

    @Upsert
    suspend fun upsert(folder: BookmarkFolderEntity)

    @Query("UPDATE bookmark_folders SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM bookmark_folders WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM bookmark_folder_members WHERE folderId = :id")
    suspend fun deleteMembersOf(id: Long)

    @Query("SELECT * FROM bookmark_folders ORDER BY sortOrder ASC, id ASC")
    fun streamFolders(): Flow<List<BookmarkFolderEntity>>

    @Query("SELECT * FROM bookmark_folders WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): BookmarkFolderEntity?

    @Query("SELECT * FROM bookmark_folder_members")
    fun streamMembers(): Flow<List<BookmarkFolderMemberEntity>>

    @Query("SELECT IFNULL(MAX(ordinal), -1) FROM bookmark_folder_members WHERE folderId = :folderId")
    suspend fun maxOrdinalIn(folderId: Long): Int

    @Query(
        "INSERT OR IGNORE INTO bookmark_folder_members (folderId, whom, postId, ordinal) " +
            "VALUES (:folderId, :whom, :postId, :ordinal)"
    )
    suspend fun addMemberRaw(folderId: Long, whom: String, postId: String, ordinal: Int)

    /** Add a bookmark to the bottom of the folder. Idempotent. */
    @Transaction
    suspend fun addMember(folderId: Long, whom: String, postId: String) {
        val next = maxOrdinalIn(folderId) + 1
        addMemberRaw(folderId, whom, postId, next)
    }

    @Query(
        "DELETE FROM bookmark_folder_members " +
            "WHERE folderId = :folderId AND whom = :whom AND postId = :postId"
    )
    suspend fun removeMember(folderId: Long, whom: String, postId: String)

    @Query(
        "SELECT folderId FROM bookmark_folder_members " +
            "WHERE whom = :whom AND postId = :postId"
    )
    suspend fun foldersContaining(whom: String, postId: String): List<Long>

    @Query(
        "UPDATE bookmark_folder_members SET ordinal = :ordinal " +
            "WHERE folderId = :folderId AND whom = :whom AND postId = :postId"
    )
    suspend fun setOrdinal(folderId: Long, whom: String, postId: String, ordinal: Int)

    /** Rewrite ordinals within a folder to match the supplied list order. */
    @Transaction
    suspend fun reorderMembers(folderId: Long, items: List<Pair<String, String>>) {
        items.forEachIndexed { i, (whom, postId) -> setOrdinal(folderId, whom, postId, i) }
    }

    /** Bulk replace — used by %settings inbound sync. */
    @Query("DELETE FROM bookmark_folders")
    suspend fun clearFolders()

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insertFolders(rows: List<BookmarkFolderEntity>)

    @Transaction
    suspend fun replaceAll(rows: List<BookmarkFolderEntity>) {
        clearFolders()
        if (rows.isNotEmpty()) insertFolders(rows)
    }

    @Query("DELETE FROM bookmark_folder_members")
    suspend fun clearMembers()

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insertMembers(rows: List<BookmarkFolderMemberEntity>)

    @Transaction
    suspend fun replaceAllMembers(rows: List<BookmarkFolderMemberEntity>) {
        clearMembers()
        if (rows.isNotEmpty()) insertMembers(rows)
    }
}
