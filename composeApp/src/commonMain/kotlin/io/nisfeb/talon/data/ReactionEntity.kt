package io.nisfeb.talon.data

import androidx.room.Entity
import androidx.compose.runtime.Immutable

/**
 * One reaction row. Composite PK (whom, postId, author) — each author
 * can have at most one reaction per post, matching Tlon's model.
 *
 * `emoji` holds the raw TlonReact shortcode (e.g. ":+1:"); the UI
 * maps it to a display character at render time.
 */
@Immutable
@Entity(tableName = "reactions", primaryKeys = ["whom", "postId", "author"])
data class ReactionEntity(
    val whom: String,
    val postId: String,
    val author: String,
    val emoji: String,
)
