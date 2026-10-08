package io.nisfeb.talon.urbit

import io.nisfeb.talon.data.NotifyLevel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Talon's notification levels, told to %activity the way Tlon's own
 * client tells it.
 *
 * %activity decides which events "notify" from a volume map per source,
 * a channel falling back to its group and a group to the ship's base.
 * Talon reads the result as its Mentions marker (the summary's
 * `notify-count`). A ship nobody has adjusted keeps Tlon's stock base,
 * where a plain post notifies, so every post in every room counted as a
 * mention while Talon's own level said "Mentions only" (a user's
 * report, 2026-10-08). Tlon's client writes a map whenever a level is
 * picked; Talon now does too, with Tlon's own maps
 * (packages/api/src/urbit/activity.ts, getVolumeMap), so both clients
 * read the same level back.
 */
internal object ActivityLevels {

    /** Every event a map names, as Tlon's client writes them. */
    val EVENTS = listOf(
        "post", "post-mention", "reply", "reply-mention", "react", "dm-react", "dm-invite", "dm-post",
        "dm-post-mention", "dm-reply", "dm-reply-mention", "group-ask", "group-join", "group-kick",
        "group-invite", "group-role", "flag-post", "flag-reply", "note-create", "note-edit",
    )

    /** Notify at every level but hush (Tlon's onEvents). */
    private val ON = setOf(
        "dm-reply", "post-mention", "reply-mention", "dm-invite", "dm-post", "dm-post-mention",
        "dm-reply-mention", "group-ask", "group-invite", "flag-post", "flag-reply",
    )

    /** Notify only at loud (Tlon's notifyOffEvents). */
    private val OFF = setOf("reply", "group-join", "group-kick", "group-role")

    /** Never an unread; they notify as posts do. */
    private val REACTS = setOf("react", "dm-react")
    private val NOTES = setOf("note-create", "note-edit")

    /**
     * Talon's level as Tlon's: "All messages" is Tlon's medium (posts,
     * mentions and replies to the owner), "Mentions only" its soft
     * (mentions and replies), "Mute" its hush.
     */
    fun tlonLevel(talonLevel: String): String = when (talonLevel) {
        NotifyLevel.ALL -> "medium"
        NotifyLevel.NONE -> "hush"
        else -> "soft"
    }

    /** Tlon's getVolumeMap(level, unreads), as it is. */
    fun volumeMap(tlonLevel: String, unreads: Boolean = true): JsonObject = buildJsonObject {
        for (e in EVENTS) {
            val notify = when {
                tlonLevel == "loud" -> true
                tlonLevel == "hush" -> false
                e == "post" || e in REACTS || e in NOTES -> tlonLevel == "medium" || tlonLevel == "default"
                e in ON -> true
                e in OFF -> false
                else -> continue
            }
            put(e, buildJsonObject {
                put("unreads", if (e in REACTS) false else unreads)
                put("notify", notify)
            })
        }
    }

    /**
     * Tlon's stock base (sur/activity.hoon, default-volumes): a ship whose
     * base still says this was never set by anyone, and a plain post
     * notifies on it.
     */
    private val STOCK = mapOf(
        "post" to (true to true), "reply" to (true to false), "react" to (false to true),
        "post-mention" to (true to true), "reply-mention" to (true to true), "dm-invite" to (true to true),
        "dm-post" to (true to true), "dm-post-mention" to (true to true), "dm-reply" to (true to true),
        "dm-reply-mention" to (true to true), "dm-react" to (false to true), "group-invite" to (true to true),
        "group-ask" to (true to true), "flag-post" to (true to true), "flag-reply" to (true to true),
        "group-kick" to (true to false), "group-join" to (true to false), "group-role" to (true to false),
        "contact" to (false to false), "note-create" to (true to true), "note-edit" to (true to true),
    )

    /**
     * Whether a base map is Tlon's stock one: absent (never set), or every
     * event it names as default-volumes has it. A map an older ship wrote
     * may lack the newer events; it is still stock if the rest agree.
     */
    fun isStock(base: JsonObject?): Boolean {
        if (base == null) return true
        if (base.isEmpty()) return false
        return base.all { (event, v) ->
            val want = STOCK[event] ?: return@all false
            val o = v as? JsonObject ?: return@all false
            o["unreads"].bool() == want.first && o["notify"].bool() == want.second
        }
    }

    private fun JsonElement?.bool(): Boolean? = runCatching { this?.jsonPrimitive?.booleanOrNull }.getOrNull()

    private fun isChannel(whom: String) = whom.startsWith("chat/") || whom.startsWith("diary/") || whom.startsWith("heap/")

    /**
     * The %activity source a Talon level is about: a channel's (which
     * needs its group's flag) or a group's ("group/<flag>", the key
     * Talon keeps a group's level under). Null for anything else: a DM
     * keeps Tlon's levels, which notify for every DM at all but hush,
     * and Talon's own level gates its pushes.
     */
    fun source(whom: String, groupFlag: String?): JsonObject? = when {
        whom.startsWith("group/") -> buildJsonObject { put("group", whom.removePrefix("group/")) }
        isChannel(whom) && groupFlag != null -> buildJsonObject {
            put("channel", buildJsonObject {
                put("nest", whom)
                put("group", groupFlag)
            })
        }
        else -> null
    }

    /** The source's key in the ship's volume settings (string-source). */
    fun settingsKey(whom: String): String? = when {
        whom.startsWith("group/") -> whom
        isChannel(whom) -> "channel/$whom"
        else -> null
    }

    /** The base, for the one write a stock ship gets. */
    val BASE: JsonObject = buildJsonObject { put("base", JsonNull) }

    /** An adjust: [volume] the map, or null to drop the source's own and fall back. */
    fun adjust(source: JsonObject, volume: JsonObject?): JsonObject = buildJsonObject {
        put("adjust", buildJsonObject {
            put("source", source)
            put("volume", volume ?: JsonNull)
        })
    }
}
