package io.nisfeb.talon.urbit

import io.nisfeb.talon.data.ChannelGroupEntity
import io.nisfeb.talon.data.GroupEntity
import kotlinx.serialization.json.JsonObject

/**
 * Pure parser for the `%groups /v2/groups` scry response. Pulled out
 * of [TlonChatRepo.bootstrapGroups] so the wire-shape contract is
 * unit-testable without standing up a UrbitChannel + Room database.
 *
 * The host-defined channel order (Tlon's "channel-order" field) is
 * captured implicitly via JSON-object iteration order — kotlinx
 * preserves insertion order, and the ship serializes its `channels`
 * map in the host's configured sequence. The home list's "host
 * order" sort reads `ChannelGroupEntity.ordinal` to surface that.
 */
data class GroupsScryResult(
    val groups: List<GroupEntity>,
    val channelGroups: List<ChannelGroupEntity>,
)

internal fun parseGroupsScry(obj: JsonObject): GroupsScryResult {
    val groups = mutableListOf<GroupEntity>()
    val channelGroups = mutableListOf<ChannelGroupEntity>()
    for ((flag, group) in obj) {
        val groupObj = group as? JsonObject ?: continue
        val meta = groupObj["meta"] as? JsonObject
        groups += GroupEntity(
            flag = flag,
            title = meta?.get("title").asStr()?.takeIf { it.isNotBlank() },
            image = meta?.get("image").asStr()?.takeIf { it.isNotBlank() },
        )
        val channels = groupObj["channels"] as? JsonObject ?: continue
        channels.entries.forEachIndexed { idx, (nest, channel) ->
            val channelObj = channel as? JsonObject
            val channelMeta = channelObj?.get("meta") as? JsonObject
            val channelTitle = channelMeta?.get("title").asStr()
                ?.takeIf { it.isNotBlank() }
            channelGroups += ChannelGroupEntity(
                nest = nest,
                groupFlag = flag,
                title = channelTitle,
                ordinal = idx,
            )
        }
    }
    return GroupsScryResult(groups, channelGroups)
}
