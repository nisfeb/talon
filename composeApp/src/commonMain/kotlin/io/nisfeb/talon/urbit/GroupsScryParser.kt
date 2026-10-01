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

/**
 * The channel kinds Talon can open. A group may list others: Tlon's
 * %buckets (12.3.0) registers file spaces in %groups as `buckets/...`,
 * and stored, each showed as a chat row that loaded nothing and could
 * not send. Left out instead; CLAUDE.md, "don't fake a feature; gate it".
 */
internal val SUPPORTED_CHANNEL_KINDS = setOf("chat", "heap", "diary", "notes")

/** Whether Talon can open the channel at [nest] (`kind/~host/name`). */
internal fun isSupportedChannel(nest: String): Boolean = nest.substringBefore('/') in SUPPORTED_CHANNEL_KINDS

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
            if (!isSupportedChannel(nest)) return@forEachIndexed
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
