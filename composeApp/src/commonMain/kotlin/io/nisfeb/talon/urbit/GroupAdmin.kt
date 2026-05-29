package io.nisfeb.talon.urbit

/**
 * Portable data types for group administration.
 *
 * In app/, these are nested inside TlonChatRepo (which depends on Room).
 * For commonMain, they live here as top-level classes so GroupAdminParser
 * can reference them without pulling in the Room/Android dependency chain.
 *
 * In Stage D, TlonChatRepo will be decomposed and these types will be
 * the canonical definitions.
 */
data class AdminGroup(
    val flag: String,
    val title: String?,
    val description: String?,
    val image: String?,
    val cover: String?,
    val members: List<AdminMember>,
    val cordonKind: String,
    val privacy: String?,
    val bannedShips: Set<String>,
    val invitedTokenByShip: Map<String, String>,
    val directInvitedShips: Set<String>,
    val pendingShips: Set<String>,
    val adminSects: Set<String>,
)

data class AdminMember(
    val ship: String,
    val sects: Set<String>,
    val isAdmin: Boolean,
)

/**
 * Decide whether [ourPatp] is allowed to pin posts in the channel
 * whose enclosing [groupFlag] is given. The group host is always
 * implicit admin (Tlon convention — the flag's leading patp is the
 * group host); for anyone else we look them up in [adminGroups],
 * which `TlonChatRepo.refreshAdminGroups()` populates with the
 * groups the user is an admin in.
 *
 * Returns false when [groupFlag] is null (channel not yet linked to
 * a group locally — happens briefly during bootstrap; better to hide
 * the affordance than to mislead).
 *
 * The implicit-host shortcut keeps the gate working before
 * [adminGroups] is populated — without it, a user would have to wait
 * for the bootstrap admin scry to complete (10-30s on a busy ship)
 * before seeing the pin option on their own host group.
 */
fun canPinInGroup(
    ourPatp: String,
    groupFlag: String?,
    adminGroups: List<AdminGroup>?,
): Boolean {
    if (groupFlag == null) return false
    val host = groupFlag.substringBefore('/')
    if (host == ourPatp) return true
    val list = adminGroups ?: return false
    return list.any { it.flag == groupFlag }
}
