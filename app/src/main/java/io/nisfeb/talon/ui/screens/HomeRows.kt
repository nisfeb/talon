package io.nisfeb.talon.ui.screens

import io.nisfeb.talon.data.FolderMemberEntity
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.ui.ContactMap

/**
 * Typed rows that make up the home-screen LazyColumn. Each carries a
 * stable `key` so reorder + expand/collapse don't thrash the list.
 */
sealed interface HomeRow {
    val key: String

    data class Header(val label: String, val tag: String) : HomeRow {
        override val key = "hdr:$tag"
    }

    /** Expandable group header row. */
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

    /** A channel inside a group, only shown when the parent is expanded. */
    data class GroupChild(
        val whom: String,
        val m: MessageEntity?,
        val unread: Int,
        val groupFlag: String,
    ) : HomeRow {
        override val key = "gchan:$groupFlag:$whom"
    }

    /** DM / club / ungrouped channel at the top level. */
    data class Flat(val m: MessageEntity, val unread: Int) : HomeRow {
        override val key = "conv:${m.whom}"
    }
}

/**
 * Build the structured home list for the "All" tab: Groups (with
 * nested channels when expanded), then Direct Messages.
 */
fun buildHomeRows(
    allConvs: List<Pair<MessageEntity, Int>>,
    contactMap: ContactMap,
    expandedGroups: Set<String>,
    allUnreads: Map<String, Int>,
    groupOrderFlags: List<String> = emptyList(),
): List<HomeRow> {
    val byWhom = allConvs.associateBy { it.first.whom }

    val out = mutableListOf<HomeRow>()

    // ───────── Groups ─────────
    // User-ordered flags come first in their saved order; any remaining
    // groups (newly joined, or never reordered) fall back to alphabetical.
    val allFlags = contactMap.allGroups().associateBy { it.flag }
    val orderedFlagsSet = groupOrderFlags.toSet()
    val orderedGroups = groupOrderFlags.mapNotNull { allFlags[it] }
    val unorderedGroups = allFlags.values
        .filter { it.flag !in orderedFlagsSet }
        .sortedBy { (it.title ?: it.flag).lowercase() }
    val groups = orderedGroups + unorderedGroups
    if (groups.isNotEmpty()) {
        out += HomeRow.Header("Groups", "groups")
        for (g in groups) {
            val channels = contactMap.channelsOfGroup(g.flag)
            // Sort channels: unread first, then most recent activity, then name.
            val sortedChannels = channels.sortedWith(
                compareByDescending<String> { (allUnreads[it] ?: 0) > 0 }
                    .thenByDescending { byWhom[it]?.first?.sentMs ?: 0L }
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
            // Always emit children so AnimatedVisibility in the
            // renderer has stable content for both enter and exit
            // animations. The renderer hides them when collapsed.
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

    // ───────── Direct Messages (and clubs, ungrouped channels) ─────────
    val dmRows = allConvs
        .filter { (m, _) -> contactMap.groupOfChannel(m.whom) == null }
        .sortedByDescending { (m, _) -> m.sentMs }
    if (dmRows.isNotEmpty()) {
        out += HomeRow.Header("Direct Messages", "dms")
        for ((m, unread) in dmRows) {
            out += HomeRow.Flat(m = m, unread = unread)
        }
    }

    return out
}

/**
 * Build the row list for a folder view. The folder's members are a
 * mix of channel/DM/club entries and whole-group entries, interleaved
 * in the user's drag-defined ordinal order.
 *
 * Group-kind members render as a collapsible GroupHead (plus
 * GroupChild children when expanded). Whom-kind members render as
 * Flat rows. Channels that are already covered by a group member in
 * the same folder are hidden from the loose-channel list — the
 * group wins to avoid duplication.
 */
fun buildFolderRows(
    members: List<FolderMemberEntity>,
    allConvs: List<Pair<MessageEntity, Int>>,
    contactMap: ContactMap,
    expandedGroups: Set<String>,
    allUnreads: Map<String, Int>,
): List<HomeRow> {
    if (members.isEmpty()) return emptyList()
    val byWhom = allConvs.associateBy { it.first.whom }
    val sorted = members.sortedBy { it.ordinal }

    // Pre-compute: which whoms are covered by a group member of this folder?
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
                        .thenByDescending { byWhom[it]?.first?.sentMs ?: 0L }
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
                // Always emit children — see buildHomeRows note.
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
                // Direct channel/DM/club reference. Skip if a group
                // member already covers this whom.
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
