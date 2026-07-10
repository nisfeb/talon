package io.nisfeb.talon.data

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One past Assistant exchange, kept for reference (docs/assistant.md).
 * Synced across devices via %settings (append-only, keyed by [gid]);
 * [AssistantHistoryDao.trim] caps the local table so it can't grow
 * without bound — trimming is local cache eviction, the ship keeps the
 * superset.
 *
 * `answer` is the final reply text. `mode` is retained for legacy rows.
 */
@Immutable
@Entity(tableName = "assistant_history")
data class AssistantHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Cross-device turn id (see [newGid]); empty for pre-sync rows. */
    val gid: String = "",
    val mode: String,
    val question: String,
    val answer: String,
    val createdAt: Long,
    /** The conversation this turn belongs to, by local
     *  [AssistantConversationEntity.id]; 0 for legacy ungrouped turns. */
    val conversationId: Long = 0,
    /** The conversation's cross-device id ([AssistantConversationEntity.gid]),
     *  used to re-link turns to the right conversation on a peer device. */
    val convGid: String = "",
)
