// TEMPORARY DUPLICATE: ported from
// app/src/main/java/io/nisfeb/talon/ui/screens/HomeRows.kt during the
// CMP desktop port (Task D4 prerequisite). Pure Kotlin already; just
// relocated into commonMain. Keep in lockstep with the production
// copy until app/ is removed in Stage F.
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
    if (groups.isNotEmpty()) {
        out += HomeRow.Header("Groups", "groups")
        for (g in groups) {
            val channels = contactMap.channelsOfGroup(g.flag)
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
