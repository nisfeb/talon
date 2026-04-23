package io.nisfeb.talon.ui.screens

import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.ui.ContactMap

/**
 * Typed rows that make up the home-screen LazyColumn. Each carries a
 * stable `key` so reorder + expand/collapse don't thrash the list.
 */
sealed interface HomeRow {
    val key: String

    /** Pinned-section conversation. Drag-reorderable. */
    data class Pinned(
        val whom: String,
        val m: MessageEntity?,
        val unread: Int,
    ) : HomeRow {
        override val key = "pin:$whom"
    }

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
 * Build the structured home list for the "All" tab: Pinned, then
 * Groups (with nested channels when expanded), then Direct Messages.
 * Pinned whoms appear only in the Pinned section — we don't repeat
 * them further down.
 */
fun buildHomeRows(
    allConvs: List<Pair<MessageEntity, Int>>,
    pinnedWhoms: List<String>,
    contactMap: ContactMap,
    expandedGroups: Set<String>,
    allUnreads: Map<String, Int>,
    groupOrderFlags: List<String> = emptyList(),
): List<HomeRow> {
    val byWhom = allConvs.associateBy { it.first.whom }
    val pinnedSet = pinnedWhoms.toSet()

    val out = mutableListOf<HomeRow>()

    // ───────── Pinned ─────────
    if (pinnedWhoms.isNotEmpty()) {
        out += HomeRow.Header("Pinned", "pinned")
        for (w in pinnedWhoms) {
            val row = byWhom[w]
            out += HomeRow.Pinned(
                whom = w,
                m = row?.first,
                unread = row?.second ?: (allUnreads[w] ?: 0),
            )
        }
    }

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
                .filter { it !in pinnedSet }
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
            if (expanded) {
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
    }

    // ───────── Direct Messages (and clubs, ungrouped channels) ─────────
    // Sort explicitly even though `allConvs` arrives sorted — don't want
    // any future upstream shuffle to land here.
    val dmRows = allConvs
        .filter { (m, _) ->
            m.whom !in pinnedSet &&
                contactMap.groupOfChannel(m.whom) == null
        }
        .sortedByDescending { (m, _) -> m.sentMs }
    if (dmRows.isNotEmpty()) {
        out += HomeRow.Header("Direct Messages", "dms")
        for ((m, unread) in dmRows) {
            out += HomeRow.Flat(m = m, unread = unread)
        }
    }

    return out
}
