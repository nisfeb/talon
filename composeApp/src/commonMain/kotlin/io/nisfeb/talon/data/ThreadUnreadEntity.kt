package io.nisfeb.talon.data

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.Index

/**
 * Per-thread unread summary, sourced from %activity's
 * `thread/<nest>/<msg>` and `dm-thread/<whom>/<msg>` source keys.
 *
 * The channel-level [UnreadEntity] aggregates the same events into a
 * single rollup row for the conversation badge; this table preserves
 * the per-thread breakdown so the message-row thread indicator can
 * tint when *that specific thread* has unread replies, and the
 * thread list can render a "New" divider above the first unread reply.
 *
 * `whom` matches MessageEntity.whom (channel nest, ship patp, or club
 * id). `parentPostId` is the top-level post's id in its conversation's
 * canonical form (bare `<da>` for channels, full `~author/<da>` for
 * DM / club writs — same convention MessageEntity.parentId uses).
 */
@Immutable
@Entity(
    tableName = "thread_unreads",
    primaryKeys = ["whom", "parentPostId"],
    indices = [Index(value = ["whom"])],
)
data class ThreadUnreadEntity(
    val whom: String,
    val parentPostId: String,
    val count: Int,
    val notifyCount: Int,
    val recencyMs: Long,
)
