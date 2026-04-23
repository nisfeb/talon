package io.nisfeb.talon.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A user-defined folder for organizing conversations. Purely local —
 * not synced to Urbit. Named "folder" rather than "group" to avoid
 * conflating with the %groups agent's first-class groups concept.
 */
@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sortOrder: Int = 0,
)

/**
 * Join table: one row per (folder, whom) membership. A conversation
 * can live in multiple folders.
 */
@Entity(
    tableName = "folder_members",
    primaryKeys = ["folderId", "whom"],
    indices = [Index("whom")],
)
data class FolderMemberEntity(
    val folderId: Long,
    val whom: String,
    /**
     * Per-folder display order. User-set via drag-reorder within the
     * folder tab. Rewritten in bulk so no fractional indexing needed.
     */
    val ordinal: Int = 0,
)
