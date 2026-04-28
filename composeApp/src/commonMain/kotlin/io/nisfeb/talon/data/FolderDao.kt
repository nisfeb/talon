// TEMPORARY DUPLICATE of app/src/main/java/io/nisfeb/talon/data/FolderDao.kt
package io.nisfeb.talon.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {

    @Insert
    suspend fun createFolder(folder: FolderEntity): Long

    @Upsert
    suspend fun upsert(folder: FolderEntity)

    @Query("UPDATE folders SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM folder_members WHERE folderId = :id")
    suspend fun deleteMembersOf(id: Long)

    @Query("SELECT * FROM folders ORDER BY sortOrder ASC, id ASC")
    fun streamFolders(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): FolderEntity?

    @Query("SELECT * FROM folder_members")
    fun streamMembers(): Flow<List<FolderMemberEntity>>

    @Query("SELECT IFNULL(MAX(ordinal), -1) FROM folder_members WHERE folderId = :folderId")
    suspend fun maxOrdinalIn(folderId: Long): Int

    @Query(
        "INSERT OR IGNORE INTO folder_members (folderId, whom, ordinal, kind) " +
            "VALUES (:folderId, :whom, :ordinal, :kind)"
    )
    suspend fun addMemberRaw(folderId: Long, whom: String, ordinal: Int, kind: String)

    /** Adds a channel/DM/club at the bottom of the folder. Idempotent. */
    @androidx.room.Transaction
    suspend fun addMember(folderId: Long, whom: String) {
        val next = maxOrdinalIn(folderId) + 1
        addMemberRaw(folderId, whom, next, FolderMemberEntity.KIND_WHOM)
    }

    /** Adds a whole group (by flag) at the bottom of the folder. Idempotent. */
    @androidx.room.Transaction
    suspend fun addGroupMember(folderId: Long, groupFlag: String) {
        val next = maxOrdinalIn(folderId) + 1
        addMemberRaw(folderId, groupFlag, next, FolderMemberEntity.KIND_GROUP)
    }

    @Query("DELETE FROM folder_members WHERE folderId = :folderId AND whom = :whom")
    suspend fun removeMember(folderId: Long, whom: String)

    @Query(
        "SELECT * FROM folder_members WHERE folderId = :folderId AND kind = 'group'"
    )
    suspend fun groupMembersOf(folderId: Long): List<FolderMemberEntity>

    @Query(
        "SELECT folderId FROM folder_members WHERE whom = :flag AND kind = 'group'"
    )
    suspend fun foldersContainingGroup(flag: String): List<Long>

    @Query("UPDATE folder_members SET ordinal = :ordinal WHERE folderId = :folderId AND whom = :whom")
    suspend fun setOrdinal(folderId: Long, whom: String, ordinal: Int)

    /** Rewrite ordinals within a folder to match the supplied list order. */
    @androidx.room.Transaction
    suspend fun reorderMembers(folderId: Long, whoms: List<String>) {
        whoms.forEachIndexed { i, w -> setOrdinal(folderId, w, i) }
    }

    @Query("SELECT folderId FROM folder_members WHERE whom = :whom")
    suspend fun foldersFor(whom: String): List<Long>

    @Query("DELETE FROM folders")
    suspend fun clearFolders()

    @Query("DELETE FROM folder_members")
    suspend fun clearMembers()

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insertFolders(folders: List<FolderEntity>)

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insertMembers(members: List<FolderMemberEntity>)

    @androidx.room.Transaction
    suspend fun replaceAll(folders: List<FolderEntity>) {
        clearFolders()
        if (folders.isNotEmpty()) insertFolders(folders)
    }

    @androidx.room.Transaction
    suspend fun replaceAllMembers(members: List<FolderMemberEntity>) {
        clearMembers()
        if (members.isNotEmpty()) insertMembers(members)
    }
}
