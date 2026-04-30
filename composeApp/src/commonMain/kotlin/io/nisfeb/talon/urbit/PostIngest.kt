package io.nisfeb.talon.urbit

import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.data.ReactionEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Pure ingestion of a %channels / %chat post JSON into the entities
 * the DB stores. Separated from [TlonChatRepo] so tests can exercise
 * seal / reply / reacts / tombstone parsing without touching Room or
 * StoryCache.
 */
internal data class IngestedPost(
    /** Non-tombstone rows to upsert. */
    val messages: List<MessageEntity>,
    /** Reactions to upsert. */
    val reactions: List<ReactionEntity>,
    /**
     * Ids of posts / replies the server has tombstoned. Caller runs
     * them through `softDelete` + `reactions.clearForPost`.
     */
    val tombstones: List<String>,
)

/**
 * Classify one post JSON (from a `posts` bag or a single `r-post.set`
 * payload). Returns all entities + deletions implied. Does NOT warm
 * [StoryCache] — caller re-derives parts lazily at render time.
 */
internal fun ingestedPost(whom: String, post: JsonElement): IngestedPost {
    val obj = post as? JsonObject ?: return empty
    if (obj.isTombstone()) {
        val id = obj["id"].asText()?.let(::undotAtom) ?: return empty
        return IngestedPost(emptyList(), emptyList(), listOf(id))
    }
    val seal = obj["seal"] as? JsonObject ?: return empty
    val id = seal["id"].asText()?.let(::undotAtom) ?: return empty
    val essay = obj["essay"] as? JsonObject ?: return empty

    val msgs = mutableListOf(pureEntity(whom, id, essay))
    val rx = mutableListOf<ReactionEntity>()
    val tombs = mutableListOf<String>()

    (seal["reacts"] as? JsonObject)?.forEach { (author, emoji) ->
        val e = emoji.asText() ?: return@forEach
        rx += ReactionEntity(whom, id, author, e)
    }

    (seal["replies"] as? JsonObject)?.forEach { (_, replyEl) ->
        val reply = replyEl as? JsonObject ?: return@forEach
        // Replies can be tombstones too — same shape check.
        if (reply.isTombstone()) {
            reply["id"].asText()?.let(::undotAtom)?.let { tombs += it }
            return@forEach
        }
        val rSeal = reply["seal"] as? JsonObject ?: return@forEach
        val rid = rSeal["id"].asText()?.let(::undotAtom) ?: return@forEach
        val rEssay = reply["reply-essay"] as? JsonObject ?: return@forEach
        msgs += pureReplyEntity(whom, id, rid, rEssay)
        (rSeal["reacts"] as? JsonObject)?.forEach { (author, emoji) ->
            val e = emoji.asText() ?: return@forEach
            rx += ReactionEntity(whom, rid, author, e)
        }
    }
    return IngestedPost(msgs, rx, tombs)
}

/** A JSON object is a server-side tombstone marker when it has `type: "tombstone"`. */
internal fun JsonObject.isTombstone(): Boolean =
    this["type"].asText() == "tombstone"

/** Pure essay → MessageEntity (top-level post). */
internal fun pureEntity(whom: String, id: String, essay: JsonObject): MessageEntity {
    val author = essay["author"].asText() ?: ""
    val sent = essay["sent"].asLong() ?: 0L
    val kind = essay["kind"].asText() ?: "/chat"
    val content = essay["content"] ?: JsonArray(emptyList())
    val merged = mergeBlobIntoContent(content, essay["blob"])
    val meta = essay["meta"] as? JsonObject
    val title = meta?.get("title").asText()?.takeIf { it.isNotBlank() }
    val image = meta?.get("image").asText()?.takeIf { it.isNotBlank() }
    val mergedStr = merged.toString()
    // Pre-warm StoryCache so the first render of this message doesn't
    // re-parse JSON we just built. We pay the parse here on the apply
    // path (already off the main thread); the render path becomes
    // free for this id.
    StoryCache.warm(id, mergedStr, merged)
    return MessageEntity(
        whom = whom,
        id = id,
        author = author,
        sentMs = sent,
        contentJson = mergedStr,
        kind = kind,
        title = title,
        image = image,
    )
}

/** Pure reply-essay → MessageEntity tagged with parentId. */
internal fun pureReplyEntity(
    whom: String,
    parentId: String,
    replyId: String,
    replyEssay: JsonObject,
): MessageEntity {
    val author = replyEssay["author"].asText() ?: ""
    val sent = replyEssay["sent"].asLong() ?: 0L
    val content = replyEssay["content"] ?: JsonArray(emptyList())
    val merged = mergeBlobIntoContent(content, replyEssay["blob"])
    val mergedStr = merged.toString()
    StoryCache.warm(replyId, mergedStr, merged)
    return MessageEntity(
        whom = whom,
        id = replyId,
        author = author,
        sentMs = sent,
        contentJson = mergedStr,
        kind = "/chat",
        parentId = parentId,
    )
}

/**
 * If [blob] carries a legacy file-upload JSON string, unpack it into
 * synthetic `{block: {file: …}}` verses and prepend them to the story
 * content. Tlon used to stuff file metadata into `blob`; we still see
 * it on older posts. No-op when [blob] is null / blank / not a
 * JSON-array / without `file` entries.
 */
internal fun mergeBlobIntoContent(
    content: JsonElement,
    blob: JsonElement?,
): JsonElement {
    val blobStr = blob.asText() ?: return content
    if (blobStr.isBlank()) return content
    val parsed = runCatching { Json.parseToJsonElement(blobStr) }.getOrNull()
        ?: return content
    val arr = parsed as? JsonArray ?: return content
    if (arr.isEmpty()) return content
    val synthetic = buildJsonArray {
        for (entry in arr) {
            val o = entry as? JsonObject ?: continue
            if (o["type"].asText() != "file") continue
            val uri = o["fileUri"].asText() ?: o["url"].asText() ?: continue
            add(buildJsonObject {
                put("block", buildJsonObject {
                    put("file", buildJsonObject {
                        put("url", uri)
                        o["name"].asText()?.let { put("name", it) }
                        o["size"].asLong()?.let { put("size", it) }
                        o["mimeType"].asText()?.let { put("mime", it) }
                    })
                })
            })
        }
    }
    if (synthetic.isEmpty()) return content
    return buildJsonArray {
        synthetic.forEach { add(it) }
        (content as? JsonArray)?.forEach { add(it) }
    }
}

private val empty = IngestedPost(emptyList(), emptyList(), emptyList())
