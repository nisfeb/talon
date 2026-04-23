package io.nisfeb.talon.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One Urbit group. `flag` is `~host/groupname`. `image` may be a URL
 * (S3-hosted avatar) or a hex color string (tint fallback); callers
 * decide how to treat it based on shape.
 */
@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val flag: String,
    val title: String?,
    val image: String?,
)

/**
 * Mapping from channel nest (`chat/~host/name`) to its enclosing
 * group's flag. Populated from the %groups scry's channels map so
 * UI code can resolve a channel's group image/title in O(1).
 */
@Entity(tableName = "channel_groups")
data class ChannelGroupEntity(
    @PrimaryKey val nest: String,
    val groupFlag: String,
)
