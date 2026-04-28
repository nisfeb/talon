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
