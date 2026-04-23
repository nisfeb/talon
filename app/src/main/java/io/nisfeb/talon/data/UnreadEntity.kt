package io.nisfeb.talon.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Unread summary per conversation, as served by %activity /v4/activity.
 * `whom` matches MessageEntity.whom — same key so joins are trivial.
 */
@Entity(tableName = "unreads")
data class UnreadEntity(
    @PrimaryKey val whom: String,
    val count: Int,
    val notifyCount: Int,
    val recencyMs: Long,
)
