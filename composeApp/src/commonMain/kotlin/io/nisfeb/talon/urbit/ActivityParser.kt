package io.nisfeb.talon.urbit

import io.nisfeb.talon.data.ThreadUnreadEntity
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
 * string-source`) supports six shapes; this resolver covers the
 * conversation-level ones:
 *  - `ship/<patp>`            → the ship          (1:1 DM)
 *  - `club/<id>`              → the club          (group DM)
 *  - `channel/<nest>`         → the channel       (group channel)
 *  - anything else            → null
 *
 * Thread / dm-thread keys deliberately resolve to null here — they
 * used to collapse onto the parent conversation's whom and feed the
 * channel-level [UnreadEntity], which double-counted: the agent's
 * `channel/<nest>` summary already reflects per-channel activity, so
 * adding the thread summaries on top inflated the count (1 new reply
 * → channel badge showed 2). Thread events now flow exclusively
 * through [sourceKeyToThreadSource] → [ThreadUnreadEntity], which
 * drives the per-thread indicator tint without touching the
 * channel rollup.
 */
internal fun sourceKeyToWhom(key: String): String? = when {
    key.startsWith("ship/") -> key.removePrefix("ship/")
    key.startsWith("club/") -> key.removePrefix("club/")
    key.startsWith("channel/") -> key.removePrefix("channel/")
    else -> null
}

/**
 * For `thread/<nest>/<msg>` and `dm-thread/<whom>/<msg>` source keys,
 * extract both the parent conversation [whom] AND the parent post id
 * the thread hangs off. Returns null for any non-thread key.
 *
 * The returned `parentPostId` is normalized to the form
 * [io.nisfeb.talon.data.MessageEntity.parentId] uses for its
 * conversation:
 *  - channels (`chat/`, `diary/`, `heap/`) → bare `<da>`
 *  - DMs / clubs → full `~author/<da>`
 *
 * Without this, all thread events for a given conversation collapse
 * onto a single [UnreadEntity] row and the per-thread breakdown
 * needed for the per-row unread tint / in-thread "New" divider is
 * lost.
 */
internal data class ThreadSource(val whom: String, val parentPostId: String)

internal fun sourceKeyToThreadSource(key: String): ThreadSource? = when {
    key.startsWith("thread/") -> {
        // Tlon shape (via desk/lib/activity-json.hoon string-source):
        //   thread/<nest=kind/~host/slug>/<dotted-da>
        // The nest is exactly 3 path segments; the tail is `scot %ud
        // time.key`, i.e. the DOTTED-decimal @da. MessageEntity stores
        // channel post ids UNDOTTED, so we undot before returning.
        val parts = key.removePrefix("thread/").split("/")
        if (parts.size < 4) null
        else ThreadSource(
            whom = parts.subList(0, 3).joinToString("/"),
            parentPostId = parts[3].undot(),
        )
    }
    key.startsWith("dm-thread/") -> {
        // Tlon shape:
        //   dm-thread/<whom-segment>/<patp>/<dotted-da>
        // whom-segment is `~ship` or `0vclub`. The trailing two
        // segments are the writ message-id: `<author-patp>/<dotted-da>`.
        // DM / club MessageEntity rows key on the full `~author/<da>`
        // form with the @da UNDOTTED, so we undot just the @da half.
        val rest = key.removePrefix("dm-thread/")
        val firstSlash = rest.indexOf('/')
        if (firstSlash <= 0) null
        else {
            val whom = rest.substring(0, firstSlash)
            val msgId = rest.substring(firstSlash + 1)
            val lastSlash = msgId.lastIndexOf('/')
            if (lastSlash <= 0) null
            else ThreadSource(
                whom = whom,
                parentPostId = msgId.substring(0, lastSlash) + "/" +
                    msgId.substring(lastSlash + 1).undot(),
            )
        }
    }
    else -> null
}

private fun String.undot(): String = replace(".", "")

/**
 * Map a `thread/` or `dm-thread/` activity summary into a
 * [ThreadUnreadEntity]. Returns null when [sourceKey] isn't a thread
 * source.
 */
internal fun toThreadUnread(sourceKey: String, summary: JsonObject): ThreadUnreadEntity? {
    val src = sourceKeyToThreadSource(sourceKey) ?: return null
    return ThreadUnreadEntity(
        whom = src.whom,
        parentPostId = src.parentPostId,
        count = summary["count"].asInt() ?: 0,
        notifyCount = summary["notify-count"].asInt() ?: 0,
        recencyMs = summary["recency"].asLong() ?: 0L,
    )
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
 * - `unread.id` — first-unread message boundary (null when caught
 *   up). Canonicalized to match MessageEntity.id and stored as
 *   [UnreadEntity.firstUnreadId] for the "New" divider anchor.
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
    // `unread` is `~` (absent) when the conversation is fully read;
    // otherwise an object whose `id` is the first-unread message id in
    // wire form (`~author/<dotted-da>`).
    val firstUnreadId = (summary["unread"] as? JsonObject)
        ?.get("id").asStr()
        ?.let { canonicalUnreadId(whom, it) }
    return UnreadEntity(
        whom = whom,
        count = count,
        notifyCount = notifyCount,
        recencyMs = recency,
        firstUnreadId = firstUnreadId,
    )
}

/**
 * Convert an activity `unread.id` (wire form `~author/<dotted-da>`)
 * into the conversation's canonical MessageEntity.id form:
 *  - channels (`chat/`, `diary/`, `heap/`) → bare UNDOTTED `@da`
 *    (channel rows key on just the time)
 *  - DM / club → `~author/<undotted-da>` (writ rows keep the author)
 *
 * `scot %ud` emits the @da dotted; MessageEntity stores it undotted
 * (see the dedupe pass in TlonChatRepo), so we strip dots either way.
 */
internal fun canonicalUnreadId(whom: String, rawId: String): String {
    val isChannel = whom.startsWith("chat/") ||
        whom.startsWith("diary/") || whom.startsWith("heap/")
    return if (isChannel) {
        rawId.substringAfterLast('/').replace(".", "")
    } else {
        val slash = rawId.lastIndexOf('/')
        if (slash < 0) rawId.replace(".", "")
        else rawId.substring(0, slash) + "/" + rawId.substring(slash + 1).replace(".", "")
    }
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
    // A %react / %dm-react names the reacted-to message in `key` and
    // carries `parent` only when that message was itself a reply. So a
    // react with a parent deep-links exactly like a reply does; one
    // without is a react on a top-level post.
    val isReplyTag = tag.contains("reply") || (tag.endsWith("react") && parentId != null)
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
