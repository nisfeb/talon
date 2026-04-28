// TEMPORARY DUPLICATE of app/src/main/java/io/nisfeb/talon/data/WatchwordEntity.kt
package io.nisfeb.talon.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One user-defined watchword term. */
@Entity(
    tableName = "watchwords",
    indices = [Index(value = ["term"], unique = true)],
)
data class WatchwordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Raw user-entered text, exact case as typed. */
    val term: String,
    /** When true, a live match fires a system notification. Always logged to feed. */
    val notify: Boolean,
    /** Unix millis when the term was added. */
    val createdMs: Long,
)

/**
 * One recorded match against an incoming or backfilled message.
 *
 * PK `(term, whom, postId)` is the dedupe rule: a live match colliding
 * with a backfill match for the same row is a no-op upsert. Two
 * different terms matching the same message produce two rows — that's
 * intentional, since the per-term feed view shows each term's hits
 * independently.
 */
@Entity(
    tableName = "watchword_hits",
    primaryKeys = ["term", "whom", "postId"],
    indices = [
        Index(value = ["term", "sentMs"]),
        Index(value = ["sentMs"]),
    ],
)
data class WatchwordHitEntity(
    val term: String,
    val whom: String,
    val postId: String,
    val sentMs: Long,
    /** Up to ~200 chars of plain text from the matching message. */
    val snippet: String,
)

/** Chat that should never produce watchword hits, regardless of which terms match. */
@Entity(tableName = "watchword_chat_excludes")
data class WatchwordChatExcludeEntity(
    @PrimaryKey val whom: String,
)
