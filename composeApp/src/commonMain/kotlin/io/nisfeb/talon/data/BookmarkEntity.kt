// TEMPORARY DUPLICATE of app/src/main/java/io/nisfeb/talon/data/BookmarkEntity.kt
package io.nisfeb.talon.data

import androidx.room.Entity

/**
 * Local bookmark on a specific post. Purely local — not synced to
 * Urbit. Keyed on (whom, postId) so the same message can't be
 * double-bookmarked.
 */
@Entity(tableName = "bookmarks", primaryKeys = ["whom", "postId"])
data class BookmarkEntity(
    val whom: String,
    val postId: String,
    val bookmarkedMs: Long,
)
