package io.nisfeb.talon.data

import androidx.room.Entity

/**
 * One Urbit chat message. Keyed by (whom, id) so a single author's post
 * across multiple DMs/clubs doesn't collide.
 *
 * whom        "~peer" (1:1 DM) or "0v..." (club/group DM)
 * id          "~author/<dotted-@da>" — server-assigned post id
 * sentMs      unix millis from the essay (author's clock)
 * contentJson the Story (Verse[]) serialized as JSON; rendered client-side
 * kind        "/chat" or "/chat/notice"
 */
@Entity(tableName = "messages", primaryKeys = ["whom", "id"])
data class MessageEntity(
    val whom: String,
    val id: String,
    val author: String,
    val sentMs: Long,
    val contentJson: String,
    val kind: String,
    val isDeleted: Boolean = false,
    /**
     * If non-null, this row is a reply under the given parent post id.
     * Top-level messages have parentId = null.
     */
    val parentId: String? = null,
)
