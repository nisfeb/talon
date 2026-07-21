package io.nisfeb.talon.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface NotesDao {

    // ---- notebooks -----------------------------------------------------

    @Query("SELECT * FROM notes_notebooks ORDER BY title COLLATE NOCASE ASC")
    fun streamNotebooks(): Flow<List<NotesNotebookEntity>>

    @Query("SELECT * FROM notes_notebooks WHERE flag = :flag")
    fun streamNotebook(flag: String): Flow<NotesNotebookEntity?>

    @Query("SELECT * FROM notes_notebooks WHERE flag = :flag")
    suspend fun notebook(flag: String): NotesNotebookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNotebooks(rows: List<NotesNotebookEntity>)

    @Query("DELETE FROM notes_notebooks WHERE flag = :flag")
    suspend fun deleteNotebook(flag: String)

    // ---- folders -------------------------------------------------------

    @Query("SELECT * FROM notes_folders WHERE flag = :flag ORDER BY name COLLATE NOCASE ASC")
    fun streamFolders(flag: String): Flow<List<NotesFolderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFolders(rows: List<NotesFolderEntity>)

    @Query("DELETE FROM notes_folders WHERE flag = :flag")
    suspend fun clearFolders(flag: String)

    @Query("DELETE FROM notes_folders WHERE flag = :flag AND folderId = :folderId")
    suspend fun deleteFolder(flag: String, folderId: Long)

    // ---- notes ---------------------------------------------------------

    @Query("SELECT * FROM notes_notes WHERE flag = :flag ORDER BY title COLLATE NOCASE ASC")
    fun streamNotes(flag: String): Flow<List<NotesNoteEntity>>

    @Query(
        """
        SELECT * FROM notes_notes
        WHERE flag = :flag AND folderId = :folderId
        ORDER BY title COLLATE NOCASE ASC
        """,
    )
    fun streamNotesInFolder(flag: String, folderId: Long): Flow<List<NotesNoteEntity>>

    @Query("SELECT * FROM notes_notes WHERE flag = :flag AND noteId = :noteId")
    fun streamNote(flag: String, noteId: Long): Flow<NotesNoteEntity?>

    @Query("SELECT * FROM notes_notes WHERE flag = :flag AND noteId = :noteId")
    suspend fun note(flag: String, noteId: Long): NotesNoteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNotes(rows: List<NotesNoteEntity>)

    @Query("DELETE FROM notes_notes WHERE flag = :flag AND noteId = :noteId")
    suspend fun deleteNote(flag: String, noteId: Long)

    @Query("DELETE FROM notes_notes WHERE flag = :flag")
    suspend fun clearNotes(flag: String)

    @Query("UPDATE notes_notes SET pending = :pending WHERE flag = :flag AND noteId = :noteId")
    suspend fun setPending(flag: String, noteId: Long, pending: Boolean)

    /**
     * Optimistic local edit: bump the body and mark it in flight without
     * touching `revision` — the host owns that, and the next echo
     * overwrites this row wholesale.
     */
    @Query(
        """
        UPDATE notes_notes SET bodyMd = :bodyMd, pending = 1, updatedAtMs = :atMs
        WHERE flag = :flag AND noteId = :noteId
        """,
    )
    suspend fun applyLocalEdit(flag: String, noteId: Long, bodyMd: String, atMs: Long)

    // ---- bulk replace --------------------------------------------------

    /**
     * Swap in a fresh snapshot of one notebook's tree. Rows the host no
     * longer has disappear, which a plain upsert wouldn't do. Local rows
     * still in flight are preserved: a scry that raced an unacked edit
     * would otherwise revert what the user just typed.
     */
    @Transaction
    suspend fun replaceTree(
        flag: String,
        folders: List<NotesFolderEntity>,
        notes: List<NotesNoteEntity>,
    ) {
        val pending = streamPendingIds(flag).toSet()
        clearFolders(flag)
        clearNotes(flag)
        if (folders.isNotEmpty()) upsertFolders(folders)
        val merged = notes.map { if (it.noteId in pending) it.copy(pending = true) else it }
        if (merged.isNotEmpty()) upsertNotes(merged)
    }

    @Query("SELECT noteId FROM notes_notes WHERE flag = :flag AND pending = 1")
    suspend fun streamPendingIds(flag: String): List<Long>

    @Query("DELETE FROM notes_notebooks")
    suspend fun clearAllNotebooks()
}
