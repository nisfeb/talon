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

    @Query("SELECT * FROM folder_members")
    fun streamMembers(): Flow<List<FolderMemberEntity>>

    @Query("INSERT OR IGNORE INTO folder_members (folderId, whom) VALUES (:folderId, :whom)")
    suspend fun addMember(folderId: Long, whom: String)

    @Query("DELETE FROM folder_members WHERE folderId = :folderId AND whom = :whom")
    suspend fun removeMember(folderId: Long, whom: String)

    @Query("SELECT folderId FROM folder_members WHERE whom = :whom")
    suspend fun foldersFor(whom: String): List<Long>
}
