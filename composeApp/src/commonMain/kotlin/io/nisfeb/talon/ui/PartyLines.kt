package io.nisfeb.talon.ui

import io.nisfeb.talon.call.PartyInvite
import io.nisfeb.talon.call.PartyLineHost
import io.nisfeb.talon.call.PartyRoom
import io.nisfeb.talon.data.GroupEntity

/**
 * One line of "who is on the party line" for the `/party` note and the
 * party-lines list. [ships] is the roster we can see (our own while on
 * the line, else the host's %on-line answer); [count] is the host's
 * occupancy, which an old host reports without names.
 */
fun partyRollCall(count: Int, ships: List<String>, nameFor: (String) -> String): String = when {
    ships.isNotEmpty() -> {
        val n = maxOf(count, ships.size)
        val names = ships.sorted().joinToString { nameFor(it) }
        if (n == 1) "1 on the party line: $names" else "$n on the party line: $names"
    }
    count == 1 -> "1 on the party line"
    count > 1 -> "$count on the party line"
    else -> "Nobody is on the party line right now"
}

/** A line the party-lines list shows: the host's room plus the group it
 *  belongs to, when we know it. [groupFlag] null means we hold an
 *  invite but are not in the group, so there is no channel to land in. */
data class PartyLineRow(
    val host: String,
    val name: String,
    val title: String,
    val groupFlag: String?,
) {
    val key: String get() = "$host/$name"
}

/** Every line we know about: rooms we host and invites we hold, each
 *  matched to the group whose flag names that room. Sorted by title. */
fun partyLineRows(
    rooms: Map<String, PartyRoom>,
    invites: Map<String, PartyInvite>,
    groups: List<GroupEntity>,
): List<PartyLineRow> {
    val byRoom = groups.associateBy { PartyLineHost.roomForGroup(it.flag) }
    fun row(host: String, name: String, title: String, boundFlag: String?): PartyLineRow {
        val group = boundFlag?.let { flag -> groups.firstOrNull { it.flag == flag } }
            ?: byRoom[host to name]
        return PartyLineRow(
            host = host,
            name = name,
            title = group?.title?.takeIf { it.isNotBlank() } ?: title.ifBlank { name },
            groupFlag = group?.flag ?: boundFlag,
        )
    }
    val hosted = rooms.map { (key, r) -> row(key.substringBefore('/'), r.name, r.title, r.groupFlag) }
    val invited = invites.values
        .filter { "${it.host}/${it.name}" !in rooms }
        .map { row(it.host, it.name, it.title, null) }
    return (hosted + invited).distinctBy { it.key }.sortedBy { it.title.lowercase() }
}
