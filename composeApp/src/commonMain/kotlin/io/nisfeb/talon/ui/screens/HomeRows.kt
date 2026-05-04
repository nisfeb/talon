package io.nisfeb.talon.ui.screens

import io.nisfeb.talon.data.FolderMemberEntity
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.ui.ContactMap

sealed interface HomeRow {
    val key: String

    data class Header(val label: String, val tag: String) : HomeRow {
        override val key = "hdr:$tag"
    }

    data class GroupHead(
        val flag: String,
        val title: String,
        val image: String?,
        val childCount: Int,
        val totalUnread: Int,
        val expanded: Boolean,
    ) : HomeRow {
        override val key = "group:$flag"
    }

    data class GroupChild(
        val whom: String,
        val m: MessageEntity?,
        val unread: Int,
        val groupFlag: String,
    ) : HomeRow {
        override val key = "gchan:$groupFlag:$whom"
    }

    data class Flat(val m: MessageEntity, val unread: Int) : HomeRow {
        override val key = "conv:${m.whom}"
    }
}

fun buildHomeRows(
    allConvs: List<Pair<MessageEntity, Int>>,
    contactMap: ContactMap,
    expandedGroups: Set<String>,
    allUnreads: Map<String, Int>,
    groupOrderFlags: List<String> = emptyList(),
    /** When [GroupChannelOrder.HostOrder], sort channels under each
     *  group head by the host-defined ordinal (captured from the
     *  %groups scry's iteration order). Default Recent keeps the
     *  unread-first → most-recent → alpha cascade. Unread channels
     *  always float to the top regardless of mode. */
    groupChannelOrder: io.nisfeb.talon.ui.GroupChannelOrder =
        io.nisfeb.talon.ui.GroupChannelOrder.Recent,
): List<HomeRow> {
    val byWhom = allConvs.associateBy { it.first.whom }

    val out = mutableListOf<HomeRow>()

    val allFlags = contactMap.allGroups().associateBy { it.flag }
    val orderedFlagsSet = groupOrderFlags.toSet()
    val orderedGroups = groupOrderFlags.mapNotNull { allFlags[it] }
    val unorderedGroups = allFlags.values
        .filter { it.flag !in orderedFlagsSet }
        .sortedBy { (it.title ?: it.flag).lowercase() }
    val groups = orderedGroups + unorderedGroups
    val ordinalByNest = contactMap.channelGroups.associate { it.nest to it.ordinal }
    if (groups.isNotEmpty()) {
        out += HomeRow.Header("Groups", "groups")
        for (g in groups) {
            val channels = contactMap.channelsOfGroup(g.flag)
            val sortedChannels = channels.sortedWith(
                compareByDescending<String> { (allUnreads[it] ?: 0) > 0 }
                    .let { primary ->
                        when (groupChannelOrder) {
                            io.nisfeb.talon.ui.GroupChannelOrder.HostOrder ->
                                primary.thenBy { ordinalByNest[it] ?: Int.MAX_VALUE }
                            io.nisfeb.talon.ui.GroupChannelOrder.Recent ->
                                primary.thenByDescending {
                                    byWhom[it]?.first?.sentMs ?: 0L
                                }
                        }
                    }
                    .thenBy { it }
            )
            val totalUnread = channels.sumOf { allUnreads[it] ?: 0 }
            val expanded = g.flag in expandedGroups
            out += HomeRow.GroupHead(
                flag = g.flag,
                title = g.title ?: g.flag,
                image = g.image,
                childCount = channels.size,
                totalUnread = totalUnread,
                expanded = expanded,
            )
            for (whom in sortedChannels) {
                val row = byWhom[whom]
                out += HomeRow.GroupChild(
                    whom = whom,
                    m = row?.first,
                    unread = allUnreads[whom] ?: 0,
                    groupFlag = g.flag,
                )
            }
        }
    }

    // Channel-shaped whoms (chat/host/name etc.) are never DMs. Filter
    // them out by shape, not just by group-membership lookup — the latter
    // returns null while group→channel state hasn't synced yet, which
    // would let the channel briefly show up in the DM section.
    val dmRows = allConvs
        .filter { (m, _) -> !isChannelNest(m.whom) && contactMap.groupOfChannel(m.whom) == null }
        .sortedByDescending { (m, _) -> m.sentMs }
    if (dmRows.isNotEmpty()) {
        out += HomeRow.Header("Direct Messages", "dms")
        for ((m, unread) in dmRows) {
            out += HomeRow.Flat(m = m, unread = unread)
        }
    }

    return out
}

private fun isChannelNest(whom: String): Boolean =
    whom.startsWith("chat/") ||
        whom.startsWith("diary/") ||
        whom.startsWith("heap/")

fun buildFolderRows(
    members: List<FolderMemberEntity>,
    allConvs: List<Pair<MessageEntity, Int>>,
    contactMap: ContactMap,
    expandedGroups: Set<String>,
    allUnreads: Map<String, Int>,
    groupChannelOrder: io.nisfeb.talon.ui.GroupChannelOrder =
        io.nisfeb.talon.ui.GroupChannelOrder.Recent,
): List<HomeRow> {
    if (members.isEmpty()) return emptyList()
    val byWhom = allConvs.associateBy { it.first.whom }
    val sorted = members.sortedBy { it.ordinal }
    val ordinalByNest = contactMap.channelGroups.associate { it.nest to it.ordinal }

    val coveredByGroup = buildSet {
        for (m in sorted) {
            if (m.kind == FolderMemberEntity.KIND_GROUP) {
                addAll(contactMap.channelsOfGroup(m.whom))
            }
        }
    }

    val out = mutableListOf<HomeRow>()
    for (m in sorted) {
        when (m.kind) {
            FolderMemberEntity.KIND_GROUP -> {
                val flag = m.whom
                val group = contactMap.allGroups().firstOrNull { it.flag == flag }
                val channels = contactMap.channelsOfGroup(flag)
                val sortedChannels = channels.sortedWith(
                    compareByDescending<String> { (allUnreads[it] ?: 0) > 0 }
                        .let { primary ->
                            when (groupChannelOrder) {
                                io.nisfeb.talon.ui.GroupChannelOrder.HostOrder ->
                                    primary.thenBy { ordinalByNest[it] ?: Int.MAX_VALUE }
                                io.nisfeb.talon.ui.GroupChannelOrder.Recent ->
                                    primary.thenByDescending {
                                        byWhom[it]?.first?.sentMs ?: 0L
                                    }
                            }
                        }
                        .thenBy { it }
                )
                val totalUnread = channels.sumOf { allUnreads[it] ?: 0 }
                val expanded = flag in expandedGroups
                out += HomeRow.GroupHead(
                    flag = flag,
                    title = group?.title ?: flag,
                    image = group?.image,
                    childCount = channels.size,
                    totalUnread = totalUnread,
                    expanded = expanded,
                )
                for (whom in sortedChannels) {
                    val row = byWhom[whom]
                    out += HomeRow.GroupChild(
                        whom = whom,
                        m = row?.first,
                        unread = allUnreads[whom] ?: 0,
                        groupFlag = flag,
                    )
                }
            }
            else -> {
                if (m.whom in coveredByGroup) continue
                val row = byWhom[m.whom]
                if (row != null) {
                    out += HomeRow.Flat(m = row.first, unread = row.second)
                }
            }
        }
    }
    return out
}
