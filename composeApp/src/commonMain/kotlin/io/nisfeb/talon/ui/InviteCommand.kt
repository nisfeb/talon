package io.nisfeb.talon.ui

import io.nisfeb.talon.data.GroupEntity

/**
 * `/invite <group> [~ship]`: invite a ship to one of our groups from the
 * composer. The group is picked from a fuzzy list, like an emoji; the
 * ship like a mention. In a DM the ship is the other side of it.
 */
private const val INVITE = "/invite "

/** The argument the caret is in, for its picker. */
sealed interface InviteArg {
    val query: String
    /** Where the argument starts, which a pick replaces from. */
    val start: Int

    data class Group(override val query: String, override val start: Int) : InviteArg
    data class Ship(override val query: String, override val start: Int) : InviteArg
}

fun detectInviteArg(text: String, cursor: Int, inDm: Boolean): InviteArg? {
    if (!text.startsWith(INVITE, ignoreCase = true) || cursor < INVITE.length || cursor > text.length) return null
    val words = text.substring(INVITE.length, cursor).split(' ')
    val start = cursor - words.last().length
    return when (words.size) {
        1 -> InviteArg.Group(words[0], start)
        2 -> if (inDm) null else InviteArg.Ship(words[1].removePrefix("@").removePrefix("~"), start)
        else -> null
    }
}

/** Groups for what was typed, best first; alphabetical when nothing was. */
fun matchGroups(query: String, groups: List<GroupEntity>, limit: Int = 6): List<GroupEntity> {
    val q = query.trim().lowercase()
    fun name(g: GroupEntity) = (g.title ?: g.flag).lowercase()
    if (q.isEmpty()) return groups.sortedBy(::name).take(limit)
    return groups.mapNotNull { g -> fuzzyScore(q, name(g), g.flag.lowercase())?.let { g to it } }
        .sortedWith(compareBy({ it.second }, { name(it.first) }))
        .map { it.first }
        .take(limit)
}

/** Lower is better: a name starting with it, containing it, then holding its letters in order. */
internal fun fuzzyScore(q: String, vararg names: String): Int? = names.mapNotNull { n ->
    when {
        n.startsWith(q) -> 0
        n.contains(q) -> 1
        q.fold(0) { i, c -> if (i < 0) i else n.indexOf(c, i).let { at -> if (at < 0) -1 else at + 1 } } >= 0 -> 2
        else -> null
    }
}.minOrNull()

sealed interface InviteParse {
    data class Ok(val flag: String, val ship: String) : InviteParse
    data class Problem(val message: String) : InviteParse
}

/**
 * A finished `/invite`, or what is missing from it. [dmShip] is the
 * other side of a DM. The ship is whatever name they typed: a @p, a
 * comet's twelve-word name, or a short name or nickname of somebody in
 * [known] -- the same names every other box takes.
 */
fun parseInvite(
    text: String,
    dmShip: String?,
    groups: List<GroupEntity>,
    known: Collection<String> = emptyList(),
    nicknameOf: (String) -> String? = { null },
): InviteParse {
    val args = text.trim().split(Regex("\\s+")).drop(1)
    val usage = "/invite <group>" + if (dmShip == null) " ~ship" else ""
    val groupArg = args.getOrNull(0) ?: return InviteParse.Problem("Pick a group: $usage")
    val flag = groups.firstOrNull { it.flag.equals(groupArg, ignoreCase = true) }?.flag
        ?: matchGroups(groupArg, groups, limit = 2).singleOrNull()?.flag
        ?: return InviteParse.Problem("No one group matches \"$groupArg\"; pick it from the list.")
    val typed = args.drop(1).joinToString(" ").trim().removePrefix("@")
    if (typed.isEmpty()) {
        val ship = dmShip ?: return InviteParse.Problem("Name who to invite: $usage")
        return InviteParse.Ok(flag, ship)
    }
    return when (val who = io.nisfeb.talon.ui.NameToShip.resolve(typed, known, nicknameOf)) {
        is NameToShip.Result.One -> InviteParse.Ok(flag, who.ship)
        else -> InviteParse.Problem(NameToShip.hint(who, typed) ?: "\"$typed\" is not a ship.")
    }
}
