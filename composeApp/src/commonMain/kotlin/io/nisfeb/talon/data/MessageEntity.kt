// TEMPORARY DUPLICATE of app/src/main/java/io/nisfeb/talon/data/MessageEntity.kt
package io.nisfeb.talon.data

import androidx.room.Entity
import androidx.room.Index

/**
 * One Urbit post. Keyed by (whom, id) so a single author's post across
 * multiple DMs/clubs/channels doesn't collide.
 *
 * whom        "~peer" (1:1 DM), "0v..." (club/group DM), or a channel
 *             nest ("chat/~host/slug", "diary/~host/slug", "heap/~host/slug")
 * id          "~author/<dotted-@da>" — server-assigned post id
 * sentMs      unix millis from the essay (author's clock)
 * contentJson the Story (Verse[]) serialized as JSON; rendered client-side
 * kind        "/chat", "/chat/notice", "/diary" (notebook), or "/heap" (gallery)
 * title       populated for notebook posts; null for chat/gallery
 * image       populated for notebook posts (cover image URL); null otherwise
 *
 * The `(whom, parentId, sentMs)` index is what makes chat-open fast:
 * the primary key only covers `whom` + `id`, so without this every
 * `stream(whom)` open had to scan-and-sort the whole conversation
 * slice. Queries it covers — chat stream, replies, reply counts,
 * latestFor, oldestIdFor, newestIdFor — all read in index order.
 */
@Entity(
    tableName = "messages",
    primaryKeys = ["whom", "id"],
    indices = [Index(value = ["whom", "parentId", "sentMs"])],
)
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
    val title: String? = null,
    val image: String? = null,
)
