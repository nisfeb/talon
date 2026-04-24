package io.nisfeb.talon.urbit

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure classifier for %groups `/v1/groups` SSE payloads. Returns a
 * [GroupEventIntent] describing what the repo should do — DAO writes
 * live elsewhere.
 *
 * Event envelope: `{flag, r-group: {<variant>: …}}`. We cover the
 * subset the home list depends on: group create/delete/meta,
 * channel add/edit/del. Other variants (seats, roles, entry) are
 * routed as [GroupEventIntent.Unknown] for now — the list UI
 * doesn't need them and admin screens refetch on demand.
 */
internal sealed interface GroupEventIntent {

    /** The whole group was created (or re-created). */
    data class CreateGroup(
        val flag: String,
        val title: String?,
        val image: String?,
        /** nest → channel title. Pulled eagerly so we don't need a refetch. */
        val channels: Map<String, String?>,
    ) : GroupEventIntent

    /** The whole group was deleted. Drop everything keyed to it. */
    data class DeleteGroup(val flag: String) : GroupEventIntent

    /** Group meta edited — refresh title / image. */
    data class EditGroupMeta(
        val flag: String,
        val title: String?,
        val image: String?,
    ) : GroupEventIntent

    /** A new channel landed in an existing group. */
    data class AddChannel(
        val flag: String,
        val nest: String,
        val title: String?,
    ) : GroupEventIntent

    /** Channel meta changed — title edited. */
    data class EditChannel(
        val flag: String,
        val nest: String,
        val title: String?,
    ) : GroupEventIntent

    /** Channel was removed from the group. */
    data class DeleteChannel(val flag: String, val nest: String) : GroupEventIntent

    /**
     * Everything we don't handle yet — seats, roles, entry, section
     * reshuffles. The home list doesn't care; admin screens re-scry
     * on open. Caller typically ignores these.
     */
    data class Unknown(val flag: String, val keys: Set<String>) : GroupEventIntent
}

/**
 * Classify one `{flag, r-group}` payload. Returns null when the shape
 * is malformed (missing flag or r-group).
 */
internal fun classifyGroupEvent(event: JsonObject): GroupEventIntent? {
    val flag = event["flag"]?.jsonPrimitive?.contentIfStr() ?: return null
    val r = event["r-group"] as? JsonObject ?: return null

    (r["create"] as? JsonObject)?.let { created ->
        val meta = created["meta"] as? JsonObject
        val channels = (created["channels"] as? JsonObject).orEmpty()
        return GroupEventIntent.CreateGroup(
            flag = flag,
            title = meta?.get("title")?.jsonPrimitive?.contentIfStr()?.takeIf { it.isNotBlank() },
            image = meta?.get("image")?.jsonPrimitive?.contentIfStr()?.takeIf { it.isNotBlank() },
            channels = channels.mapValues { (_, ch) ->
                val chObj = ch as? JsonObject
                val chMeta = chObj?.get("meta") as? JsonObject
                chMeta?.get("title")?.jsonPrimitive?.contentIfStr()?.takeIf { it.isNotBlank() }
            },
        )
    }

    // `delete` is either null or missing — we just care that the key exists.
    if (r.containsKey("delete")) {
        return GroupEventIntent.DeleteGroup(flag)
    }

    (r["meta"] as? JsonObject)?.let { meta ->
        return GroupEventIntent.EditGroupMeta(
            flag = flag,
            title = meta["title"]?.jsonPrimitive?.contentIfStr()?.takeIf { it.isNotBlank() },
            image = meta["image"]?.jsonPrimitive?.contentIfStr()?.takeIf { it.isNotBlank() },
        )
    }

    (r["channel"] as? JsonObject)?.let { channel ->
        val nest = channel["nest"]?.jsonPrimitive?.contentIfStr()
            ?: return GroupEventIntent.Unknown(flag, channel.keys)
        val rChannel = channel["r-channel"] as? JsonObject
            ?: return GroupEventIntent.Unknown(flag, channel.keys)
        (rChannel["add"] as? JsonObject)?.let { added ->
            val chMeta = added["meta"] as? JsonObject
            return GroupEventIntent.AddChannel(
                flag = flag,
                nest = nest,
                title = chMeta?.get("title")?.jsonPrimitive?.contentIfStr()
                    ?.takeIf { it.isNotBlank() },
            )
        }
        (rChannel["edit"] as? JsonObject)?.let { edited ->
            val chMeta = edited["meta"] as? JsonObject
            return GroupEventIntent.EditChannel(
                flag = flag,
                nest = nest,
                title = chMeta?.get("title")?.jsonPrimitive?.contentIfStr()
                    ?.takeIf { it.isNotBlank() },
            )
        }
        if (rChannel.containsKey("del")) {
            return GroupEventIntent.DeleteChannel(flag, nest)
        }
        // join / section / other — not list-relevant.
        return GroupEventIntent.Unknown(flag, rChannel.keys)
    }

    return GroupEventIntent.Unknown(flag, r.keys)
}

private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())

private fun JsonPrimitive.contentIfStr(): String? =
    if (isString) content else null
