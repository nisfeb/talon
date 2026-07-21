package io.nisfeb.talon.data

import androidx.room.Entity
import androidx.room.Index

/**
 * Local cache of %notes state — Tlon v12's Markdown "Notebook" channels.
 *
 * Rows are keyed by `flag` (the notebook's `~host/name`) plus the
 * server-assigned id, because ids are only unique within a notebook.
 * Everything here is a replica of host state: the repo replaces it from
 * scries and stream updates, so it's safe to wipe (the database uses
 * destructive migration and refills from the ship).
 *
 * Timestamps are millis. %notes emits Unix seconds; NotesWire converts
 * on the way in so nothing below this line has to know that.
 */
@Entity(
    tableName = "notes_notebooks",
    primaryKeys = ["flag"],
)
data class NotesNotebookEntity(
    /** `~host/name`. */
    val flag: String,
    val notebookId: Long,
    val title: String,
    val visibility: String,
    val createdBy: String,
    val createdAtMs: Long,
    val updatedBy: String,
    val updatedAtMs: Long,
    /** Group nest this notebook belongs to, when it's a group channel. */
    val groupFlag: String? = null,
)

@Entity(
    tableName = "notes_folders",
    primaryKeys = ["flag", "folderId"],
    indices = [Index(value = ["flag", "parentFolderId"])],
)
data class NotesFolderEntity(
    val flag: String,
    val folderId: Long,
    val name: String,
    /** null for the notebook's root folder. */
    val parentFolderId: Long?,
    val createdBy: String,
    val createdAtMs: Long,
    val updatedBy: String,
    val updatedAtMs: Long,
)

@Entity(
    tableName = "notes_notes",
    primaryKeys = ["flag", "noteId"],
    indices = [Index(value = ["flag", "folderId"])],
)
data class NotesNoteEntity(
    val flag: String,
    val noteId: Long,
    val folderId: Long,
    val title: String,
    val slug: String?,
    val bodyMd: String,
    val createdBy: String,
    val createdAtMs: Long,
    val updatedBy: String,
    val updatedAtMs: Long,
    /** Host revision; echoed back on edit so the host can reject clobbers. */
    val revision: Long,
    /**
     * Set while a local edit is in flight and cleared when the host
     * echoes it back. Lets the UI show a pending state and lets a failed
     * write be surfaced rather than silently lost.
     */
    val pending: Boolean = false,
)
