// TEMPORARY DUPLICATE of app/src/main/java/io/nisfeb/talon/data/BookmarkFolderEntity.kt
package io.nisfeb.talon.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * User-defined folder for organizing bookmarks. Mirrors the schema +
 * UX of `FolderEntity` (the conversation folders); kept as a separate
 * table so bookmarks and conversations don't share a namespace.
 *
 * Synced to %settings under bucket `bookmark-folders` so the
 * grouping survives a reinstall and follows the user across devices.
 */
@Entity(tableName = "bookmark_folders")
data class BookmarkFolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sortOrder: Int = 0,
)

/**
 * Join table: one row per (folder, bookmark) membership. A bookmark
 * may be filed under multiple folders (vs. conversation folders, but
 * matching that pattern). Composite PK on (folderId, whom, postId)
 * dedupes; the (whom, postId) index speeds up "which folders is this
 * bookmark in?" lookups, mirroring the conversation folder pattern.
 */
@Entity(
    tableName = "bookmark_folder_members",
    primaryKeys = ["folderId", "whom", "postId"],
    indices = [Index("whom", "postId")],
)
data class BookmarkFolderMemberEntity(
    val folderId: Long,
    val whom: String,
    val postId: String,
    /** Per-folder display order. User-set via drag-reorder; rewritten in bulk. */
    val ordinal: Int = 0,
)
