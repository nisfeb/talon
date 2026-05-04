package io.nisfeb.talon.data

import androidx.room.Entity
import androidx.room.Index
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
 *
 * Index on `groupFlag` — `streamChannelsForGroup` and
 * `deleteChannelsForGroup` both filter by it, and without the index
 * each call scanned the whole channel_groups table (hundreds of rows
 * for an active user).
 */
@Entity(
    tableName = "channel_groups",
    indices = [Index(value = ["groupFlag"])],
)
data class ChannelGroupEntity(
    @PrimaryKey val nest: String,
    val groupFlag: String,
    /** Display title from the channel's meta block, if set. */
    val title: String? = null,
    /**
     * Pinned post id (undotted @ud), or null if nothing is pinned.
     * Tlon stores the full `channel.order` array but renders only the
     * first element — we track just that, which is simpler and matches
     * the feature's actual surface (one pinned post per channel).
     */
    val pinnedPostId: String? = null,
    /**
     * Host-defined channel ordinal within its group, captured from the
     * iteration order of the `%groups /v2/groups` scry's `channels`
     * map at bootstrap time. Lets the home list expose a "host order"
     * sort option alongside "most recently active". Equal ordinals
     * fall back to the existing alpha-by-nest tiebreaker. Default 0
     * keeps pre-migration rows from jumping to the top until the
     * next bootstrap fills them in.
     */
    val ordinal: Int = 0,
)
