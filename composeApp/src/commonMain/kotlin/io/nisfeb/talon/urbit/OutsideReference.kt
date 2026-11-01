package io.nisfeb.talon.urbit

import io.nisfeb.talon.data.MessageEntity
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * What a channel's host says of one post to a ship that may not be in
 * its group: %channels' said. A user's report (2026-11-01): a reference
 * to a post in a public group they were not in showed only the
 * channel's id, though %groups lets anyone read a public group's open
 * channels.
 */
sealed interface Said {
    data class Post(val message: MessageEntity) : Said

    /** The host will not show it: a private group, or a channel only some of its members read. */
    data object Denied : Said

    /** The host has no such post or reply: deleted, or never there. */
    data object Gone : Said

    /** No answer: the host is down or slow, or the answer could not be read. */
    data object NoAnswer : Said
}

/** A channel this ship is not in, as its host's %groups shows it to anyone who may read it. */
data class ChannelPreview(val channelTitle: String?, val groupFlag: String, val groupTitle: String?)

/**
 * The said watch for one post on this ship's own %channels: /v2, which
 * asks the host's channels-server and answers once, the post or
 * %channel-denied, then kicks. [postDa] and [replyDa] are dotted, as a
 * cite carries them. Not /v4 or /v5, Tlon's own: for a post this ship
 * does not hold, those forward to the host's %channels on a path with no
 * ask, which it crashes on (bad-watch-path), and come back denied
 * (%groups 12.2.0 and v12.4.0, seen on two ships 2026-11-01).
 */
internal fun saidPath(nest: String, postDa: String, replyDa: String?): String =
    "/v2/said/$nest/post/$postDa" + (replyDa?.let { "/$it" } ?: "")

/** %groups' preview of a channel, asked of its host on this ship's behalf. */
internal fun channelPreviewPath(nest: String): String = "/v1/channels/$nest/preview"

/**
 * The said fact's JSON as a [Said]: `{nest, reference: {post} | {reply}}`
 * (channel-json's said:v8). %channel-denied's JSON is null. Null here is
 * no fact at all. A post the host lacks comes as a stand-in whose kind
 * ends /unknown or /deleted, and a reply as one with a blank seal, id 0
 * (channel-utils' said-2): [Said.Gone], not a post by ~zod in 2000.
 */
internal fun parseSaid(nest: String, json: JsonElement?): Said {
    if (json == null) return Said.NoAnswer
    if (json is JsonNull) return Said.Denied
    val reference = (json as? JsonObject)?.get("reference") as? JsonObject ?: return Said.NoAnswer
    (reference["post"] as? JsonObject)?.let { post ->
        val id = (post["seal"] as? JsonObject)?.get("id").asText() ?: return Said.NoAnswer
        val essay = post["essay"] as? JsonObject ?: return Said.NoAnswer
        val kind = essay["kind"].asText().orEmpty()
        if (kind.endsWith("/unknown") || kind.endsWith("/deleted")) return Said.Gone
        return Said.Post(pureEntity(nest, undotAtom(id), essay))
    }
    (reference["reply"] as? JsonObject)?.let { found ->
        val parent = found["id-post"].asText() ?: return Said.NoAnswer
        val reply = found["reply"] as? JsonObject ?: return Said.NoAnswer
        val id = (reply["seal"] as? JsonObject)?.get("id").asText() ?: return Said.NoAnswer
        val memo = reply["memo"] as? JsonObject ?: return Said.NoAnswer
        if (undotAtom(id) == "0") return Said.Gone
        return Said.Post(pureReplyEntity(nest, undotAtom(parent), undotAtom(id), memo))
    }
    return Said.NoAnswer
}

/** The channel preview fact's JSON (`{nest, meta, group: {flag, meta}}`), or null. */
internal fun parseChannelPreview(json: JsonElement?): ChannelPreview? {
    val preview = json as? JsonObject ?: return null
    val group = preview["group"] as? JsonObject ?: return null
    val flag = group["flag"].asText() ?: return null
    fun JsonElement?.title() = (this as? JsonObject)?.get("title").asText()?.takeIf { it.isNotBlank() }
    return ChannelPreview(preview["meta"].title(), flag, group["meta"].title())
}

/** A cite's channel kinds: the only nests a said or a preview can be asked for. */
internal fun isChannelNest(whom: String): Boolean =
    whom.startsWith("chat/") || whom.startsWith("heap/") || whom.startsWith("diary/")
