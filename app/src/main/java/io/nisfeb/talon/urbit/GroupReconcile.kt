package io.nisfeb.talon.urbit

import io.nisfeb.talon.data.ChannelGroupEntity
import io.nisfeb.talon.data.GroupEntity

/**
 * Pure diff between locally-cached groups and what %groups `/v2/groups`
 * reports live. Feeds [bootstrapGroups]' reconciliation pass.
 *
 * We ran without this for a while and a user who left / deleted a
 * group via another client saw a zombie row in the home list forever.
 * Live delete events via [GroupEventIntent.DeleteGroup] clean the DB
 * in-session, but a delete that happens while Talon is offline is
 * invisible until the next full scry — and the pre-reconcile bootstrap
 * only upserted, never removed.
 *
 * Returns the set of group flags + channel nests that exist locally
 * but are absent from the live scry; the caller deletes them before
 * upserting the live data.
 */
internal data class GroupReconcilePlan(
    val deletedGroupFlags: Set<String>,
    val deletedChannelNests: Set<String>,
)

internal fun planGroupReconcile(
    existingGroups: List<GroupEntity>,
    existingChannels: List<ChannelGroupEntity>,
    liveGroupFlags: Set<String>,
    liveChannelNests: Set<String>,
): GroupReconcilePlan {
    val deletedGroups = existingGroups
        .filter { it.flag !in liveGroupFlags }
        .map { it.flag }
        .toSet()
    val deletedChannels = existingChannels
        .filter { cg ->
            // Gone if its enclosing group is gone (host deleted / we
            // left) OR the channel alone was removed from a still-
            // present group while we were offline.
            cg.groupFlag !in liveGroupFlags || cg.nest !in liveChannelNests
        }
        .map { it.nest }
        .toSet()
    return GroupReconcilePlan(
        deletedGroupFlags = deletedGroups,
        deletedChannelNests = deletedChannels,
    )
}
