package io.nisfeb.talon.data

import androidx.room.Entity
import androidx.compose.runtime.Immutable
import androidx.room.PrimaryKey

/**
 * Unread summary per conversation, as served by %activity /v4/activity.
 * `whom` matches MessageEntity.whom — same key so joins are trivial.
 */
@Immutable
@Entity(tableName = "unreads")
data class UnreadEntity(
    @PrimaryKey val whom: String,
    val count: Int,
    val notifyCount: Int,
    val recencyMs: Long,
    /**
     * Id of the first unread message in this conversation, taken from
     * %activity's `unread.unread-point.id` (null when caught up). This
     * is the server's own "new starts here" boundary — the chat
     * screen anchors its "New" divider above this exact message
     * rather than guessing from [count] (which counts unread *events*
     * — reactions, replies — not just new top-level messages, so a
     * count-from-end placement mis-positioned the divider and showed
     * one even when nothing new had been posted).
     *
     * Stored in the conversation's canonical id form (bare undotted
     * `@da` for channels, `~author/<undotted-da>` for DM / club) so it
     * matches MessageEntity.id directly.
     */
    val firstUnreadId: String? = null,
)
