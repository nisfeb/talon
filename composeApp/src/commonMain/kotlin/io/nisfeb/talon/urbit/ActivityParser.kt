// TEMPORARY DUPLICATE of app/src/main/java/io/nisfeb/talon/urbit/ActivityParser.kt
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
 * locally. Tlon's source-key encoding (see `desk/lib/activity-json.hoon
 * string-source`) supports six shapes; we map them all back to a
 * `whom` that points at the parent conversation:
 *  - `ship/<patp>`            → the ship          (1:1 DM)
 *  - `club/<id>`              → the club          (group DM)
 *  - `channel/<nest>`         → the channel       (group channel)
 *  - `thread/<nest>/<msg>`    → the channel       (top-level chat)
 *  - `dm-thread/<whom>/<msg>` → the ship/club     (top-level DM)
 *  - anything else (`group/`, `base`, `contact/`) → null (we don't
 *    surface those events in the per-conversation home list).
 */
internal fun sourceKeyToWhom(key: String): String? = when {
    key.startsWith("ship/") -> key.removePrefix("ship/")
    key.startsWith("club/") -> key.removePrefix("club/")
    key.startsWith("channel/") -> key.removePrefix("channel/")
    key.startsWith("thread/") -> {
        // `thread/<nest>/<msg-id>`. The nest is itself
        // `<kind>/~host/<slug>` (3 parts). Tail is the msg id.
        val parts = key.removePrefix("thread/").split("/")
        if (parts.size >= 3) parts.subList(0, 3).joinToString("/") else null
    }
    key.startsWith("dm-thread/") -> {
        // `dm-thread/<whom>/<msg-id>`. The whom is one path segment —
        // either `~ship` or `0vclub` — and the msg-id has its own
        // slashes that we don't need.
        key.removePrefix("dm-thread/").substringBefore('/').takeIf { it.isNotEmpty() }
    }
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
/**
 * Pull the post id + parent post id out of a single %activity event
 * object. Tlon's events use a few overlapping shapes:
 *  - `key.id` is the message id of the event (post or reply)
 *  - `parent.id` is the parent post id when the event is about a reply
 *  - some shapes use `top` for the parent (e.g. dm-reply-mention)
 *  - older / shorter shapes carry `id` directly without a `key` wrap
 *
 * The tag tells us whether to interpret the event as a reply
 * ("reply", "dm-reply", "reply-mention", "dm-reply-mention") — for
 * those, we re-target so callers can deep-link into the right thread
 * + reply. Returns ids in their canonical undotted form.
 */
internal data class ActivityEventTarget(
    val postId: String?,
    val parentPostId: String?,
)

/**
 * Normalize a wire-form post id to the form Talon's DB stores for the
 * given conversation. Activity events emit `~author/<da>` everywhere
 * (the `message-key = [author=ship time=da]` shape); our channel
 * tables key on the bare `<da>`, while DM and club tables key on the
 * full `~author/<da>`. Without this normalization, deep-link lookups
 * for channel threads miss the parent and the thread renders blank.
 *
 * Returns null when [rawId] is null. Also handles unprefixed input —
 * if the wire id arrived without the author segment it's already in
 * channel form.
 */
internal fun canonicalPostIdForWhom(whom: String?, rawId: String?): String? {
    if (rawId == null) return null
    val isChannel = whom?.let {
        it.startsWith("chat/") || it.startsWith("diary/") || it.startsWith("heap/")
    } == true
    return if (isChannel) rawId.substringAfterLast('/') else rawId
}

internal fun parseActivityEventTarget(tag: String, eventObj: JsonObject): ActivityEventTarget {
    val keyId = (eventObj["key"] as? JsonObject)?.get("id").asStr()
        ?: eventObj["id"].asStr()
    val parentId = (eventObj["parent"] as? JsonObject)?.get("id").asStr()
        ?: (eventObj["top"] as? JsonObject)?.get("id").asStr()
    val isReplyTag = tag.contains("reply")
    return when {
        isReplyTag && parentId != null ->
            ActivityEventTarget(keyId?.let(::undotAtom), parentId.let(::undotAtom))
        isReplyTag && parentId == null ->
            ActivityEventTarget(null, keyId?.let(::undotAtom))
        else ->
            ActivityEventTarget(keyId?.let(::undotAtom), null)
    }
}

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
                    // deep=true recurses into child sources. For a
                    // channel that means the per-post `thread/<nest>/<msg>`
                    // sources spawned by diary comments / heap reactions;
                    // for a DM that means `dm-thread/<whom>/<msg>`. These
                    // child sources collapse onto the same `whom` in our
                    // unreads table, so without recursion the badge
                    // refuses to clear on diary / heap channels (and on
                    // any chat with reply traffic).
                    put("time", kotlinx.serialization.json.JsonNull)
                    put("deep", true)
                })
            })
        })
    }
