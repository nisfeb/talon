package io.nisfeb.talon.urbit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Parse a single group JSON object (as returned by the `groups`
 * `/v2/groups/<flag>` scry) into an [AdminGroup].
 *
 * Extracted so tests can exercise the schema translation (seats /
 * admins / admissions) without spinning up a live channel.
 */
internal fun parseAdminGroup(flag: String, obj: JsonObject): AdminGroup {
    val meta = obj["meta"] as? JsonObject
    fun metaStr(key: String) = meta?.get(key).asStr()
        ?.takeIf { it.isNotBlank() }

    val host = flag.substringBefore('/')
    // Schema renamed: `bloc` → `admins`, `fleet` → `seats`,
    // `cordon` → `admissions`, `sects` → `roles`. Keep fallbacks
    // to the older names for ships still on the legacy agent.
    val adminArr = (obj["admins"] ?: obj["bloc"]) as? JsonArray
    val adminSects = adminArr?.mapNotNull { it.asStr() }
        ?.toSet() ?: emptySet()

    val members = (obj["seats"] ?: obj["fleet"]) as? JsonObject
    val mems = members?.mapNotNull { (ship, entry) ->
        val entryObj = entry as? JsonObject ?: return@mapNotNull null
        val rolesArr = (entryObj["roles"] ?: entryObj["sects"]) as? JsonArray
            ?: JsonArray(emptyList())
        val sects = rolesArr.mapNotNull { it.asStr() }.toSet()
        AdminMember(
            ship = ship,
            sects = sects,
            isAdmin = ship == host || "admin" in sects ||
                adminSects.any { it in sects },
        )
    }?.sortedBy { it.ship } ?: emptyList()

    val admissions = (obj["admissions"] ?: obj["cordon"]) as? JsonObject
    // New flat admissions schema has: banned, invited, pending,
    // privacy, tokens, requests — no tagged union. Privacy drives
    // whether we treat the group as open (public) or shut
    // (private/secret) for back-compat with legacy pokes.
    val privacy = admissions?.get("privacy").asStr()
    val cordonKind = when {
        privacy == "public" -> "open"
        privacy != null -> "shut"
        admissions?.get("open") is JsonObject -> "open"
        admissions?.get("shut") is JsonObject -> "shut"
        else -> "shut"
    }
    val bannedSet = ((admissions?.get("banned") as? JsonObject)
        ?.get("ships") as? JsonArray)
        ?: ((admissions?.get("open") as? JsonObject)?.get("ships") as? JsonArray)
    val bannedShips = bannedSet?.mapNotNull { it.asStr() }
        ?.toSet() ?: emptySet()
    val seatKeys = (obj["seats"] as? JsonObject)?.keys ?: emptySet()
    val invitedMap = (admissions?.get("invited") as? JsonObject)
        ?.mapNotNull { (ship, v) ->
            if (ship in seatKeys) return@mapNotNull null
            val token = (v as? JsonObject)?.get("token").asStr()
                ?: return@mapNotNull null
            ship to token
        }?.toMap() ?: emptyMap()
    val directInvitedShips = (admissions?.get("pending") as? JsonObject)
        ?.keys?.filterNot { it in seatKeys }?.toSet() ?: emptySet()
    val pendingShips = (admissions?.get("requests") as? JsonObject)
        ?.keys?.toSet() ?: emptySet()

    return AdminGroup(
        flag = flag,
        title = metaStr("title"),
        description = metaStr("description"),
        image = metaStr("image"),
        cover = metaStr("cover"),
        members = mems,
        cordonKind = cordonKind,
        privacy = privacy,
        bannedShips = bannedShips,
        invitedTokenByShip = invitedMap,
        directInvitedShips = directInvitedShips,
        pendingShips = pendingShips,
        adminSects = adminSects,
    )
}
