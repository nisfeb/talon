package io.nisfeb.talon.data

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One past Assistant exchange, kept for reference (docs/assistant.md).
 * Local-only — history is per-device and not synced through %settings;
 * it's a convenience log, not shared state. [AssistantHistoryDao.trim]
 * caps the table so it can't grow without bound.
 *
 * `answer` is the final reply text for both modes (Ask's grounded
 * answer, or Act's last spoken line). `mode` is "Ask" or "Act".
 */
@Immutable
@Entity(tableName = "assistant_history")
data class AssistantHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mode: String,
    val question: String,
    val answer: String,
    val createdAt: Long,
    /** The conversation this turn belongs to
     *  ([AssistantConversationEntity.id]); 0 for legacy ungrouped turns. */
    val conversationId: Long = 0,
)
