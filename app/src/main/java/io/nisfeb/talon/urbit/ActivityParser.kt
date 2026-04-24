package io.nisfeb.talon.urbit

import io.nisfeb.talon.data.UnreadEntity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Pure classifiers for %activity update payloads. Source-key parsing,
 * read-action source building, and summary → UnreadEntity mapping all
 * live here so tests can pin them down without spinning up a ship.
 *
 * When %activity's schema changes (it has — `activity/v4` etc.) these
 * are the first things to check.
 */

/**
 * Split `"<kind>/<rest>"` source keys into a `whom` we can look up
 * locally. Kinds we don't surface (`thread/`, `group/`, `base`) return
 * null so the caller can skip them.
 */
internal fun sourceKeyToWhom(key: String): String? = when {
    key.startsWith("ship/") -> key.removePrefix("ship/")
    key.startsWith("club/") -> key.removePrefix("club/")
    key.startsWith("channel/") -> key.removePrefix("channel/")
    else -> null
}

/**
 * Extract a `whom` from a structured activity source object — shape
 * used by read-deltas where the source is nested (`{dm: {ship}}` etc.)
 * rather than string-keyed.
 */
internal fun sourceToWhom(source: JsonObject): String? {
    (source["dm"] as? JsonObject)?.let { dm ->
        dm["ship"].asStr()?.let { return it }
        dm["club"].asStr()?.let { return it }
    }
    (source["channel"] as? JsonObject)?.let { ch ->
        ch["nest"].asStr()?.let { return it }
    }
    return null
}

/**
 * Map an activity summary to an [UnreadEntity] for [whom]. Returns
 * null when the source-kind isn't something we surface.
 *
 * - `count` — total unread items (channel posts, DM writs, thread
 *   replies — we treat all three uniformly for badge purposes).
 * - `notify-count` — subset that should ping the user (@-mentions,
 *   replies to our own posts). Drives the Mentions tab.
 * - `recency` — last-event ms, used to sort the home list.
 */
internal fun toUnread(
    sourceKey: String?,
    summary: JsonObject,
    overrideWhom: String? = null,
): UnreadEntity? {
    val whom = overrideWhom
        ?: sourceKeyToWhom(sourceKey ?: return null)
        ?: return null
    val count = summary["count"].asInt() ?: 0
    val notifyCount = summary["notify-count"].asInt() ?: 0
    val recency = summary["recency"].asLong() ?: 0L
    return UnreadEntity(
        whom = whom,
        count = count,
        notifyCount = notifyCount,
        recencyMs = recency,
    )
}

/**
 * Build the source object used in `activity-action.read.source` pokes.
 * Returns null when [whom] is a channel but we don't know its group
 * (caller must resolve the group flag first).
 */
internal fun activityReadSource(whom: String, groupFlag: String? = null): JsonObject? {
    return when {
        whom.startsWith("~") -> buildJsonObject {
            put("dm", buildJsonObject { put("ship", whom) })
        }
        whom.startsWith("0v") -> buildJsonObject {
            put("dm", buildJsonObject { put("club", whom) })
        }
        whom.startsWith("chat/") ||
            whom.startsWith("diary/") ||
            whom.startsWith("heap/") -> {
            groupFlag ?: return null
            buildJsonObject {
                put("channel", buildJsonObject {
                    put("nest", whom)
                    put("group", groupFlag)
                })
            }
        }
        else -> null
    }
}

/**
 * Build the full `activity-action` poke body for marking a
 * conversation read. Caller supplies the source object (built with
 * [activityReadSource]).
 */
internal fun activityReadAction(source: JsonObject): JsonObject =
    buildJsonObject {
        put("read", buildJsonObject {
            put("source", source)
            put("action", buildJsonObject {
                put("all", buildJsonObject {
                    put("time", kotlinx.serialization.json.JsonNull)
                    put("deep", false)
                })
            })
        })
    }
