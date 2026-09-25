package io.nisfeb.talon.data

import androidx.room.Entity
import androidx.compose.runtime.Immutable
import androidx.room.Index
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

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
@Immutable
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
    /**
     * Send-state for our own outgoing messages:
     *  - null: not tracked (server-echoed rows, and DMs in flight).
     *  - "pending": an optimistic channel post; the poke is in flight.
     *  - "failed": the ship refused the poke, or it never left; the
     *    message didn't land. The row stays so the UI can say so.
     * "sent" isn't stored — once the server echoes the post under its
     * real id, MessageDao.reapLocalTwin removes the pending row and
     * inserts a fresh status=null row, which is the implicit "sent".
     */
    val status: String? = null,
    /**
     * What search and watchword scans match: the title and the words as
     * shown. [MessageDao]'s writes fill it; null only on rows from before
     * it existed, until [TlonChatRepo] start fills those in.
     */
    val searchText: String? = null,
)

/** 48 to 49: [MessageEntity.searchText]. Old rows get theirs once the repo starts. */
internal const val MESSAGE_SEARCH_TEXT_SQL = "ALTER TABLE messages ADD COLUMN searchText TEXT"

/** See [MESSAGE_SEARCH_TEXT_SQL]. Android runs the same statement its own way. */
val MESSAGE_SEARCH_TEXT_MIGRATION = object : Migration(48, 49) {
    override fun migrate(connection: SQLiteConnection) = connection.execSQL(MESSAGE_SEARCH_TEXT_SQL)
}
