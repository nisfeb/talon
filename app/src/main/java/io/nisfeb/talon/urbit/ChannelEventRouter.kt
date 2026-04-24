package io.nisfeb.talon.urbit

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * Pure classifier for a single %channels SSE delta payload. Takes the
 * `response` object (`{post: …}` / `{posts: …}` / `{pending: …}`) and
 * returns a sealed intent that the repo turns into DB writes.
 *
 * Separated from [TlonChatRepo.applyChannelDelta] so that the schema
 * translation — the part that keeps flipping under us — is covered by
 * tests without needing Room / coroutines.
 */
internal sealed interface ChannelDeltaIntent {
    /** Multiple posts bundled (init / recovery). Raw content bag. */
    data class PostsBatch(val posts: JsonObject) : ChannelDeltaIntent

    /** Server added a full post. `post` is the full r-post.set payload. */
    data class PostSet(val id: String, val post: JsonObject) : ChannelDeltaIntent

    /** Server tombstoned the top-level post. */
    data class PostTombstone(val id: String) : ChannelDeltaIntent

    /** Older `r-post: {set: null}` shape — same intent as tombstone. */
    data class PostDeleted(val id: String) : ChannelDeltaIntent

    /** Server updated the reactions map for a post. */
    data class PostReactions(val id: String, val reacts: JsonObject) : ChannelDeltaIntent

    /** Server edited a post's essay (content/meta). */
    data class PostEssay(val id: String, val essay: JsonObject) : ChannelDeltaIntent

    /** Reply delta nested under a parent post. */
    data class Reply(
        val parentId: String,
        val replyId: String,
        val inner: ReplyIntent,
    ) : ChannelDeltaIntent

    /** Optimistic pending insert — the server is about to commit. */
    data class PendingPost(val payload: JsonObject) : ChannelDeltaIntent

    /** Anything we don't recognize — caller can ignore or log. */
    data class Unknown(val keys: Set<String>) : ChannelDeltaIntent
}

internal sealed interface ReplyIntent {
    data class Upsert(val replyEssay: JsonObject) : ReplyIntent
    data object Tombstone : ReplyIntent
    data object Deleted : ReplyIntent   // legacy `set: null`
    data class Reactions(val reacts: JsonObject) : ReplyIntent
    data class Unknown(val keys: Set<String>) : ReplyIntent
}

/**
 * Classify a single channel SSE response object.
 *
 * Normalizes ids (strips dot-grouping) so downstream DB lookups match
 * regardless of server echo format.
 */
internal fun classifyChannelDelta(response: JsonObject): ChannelDeltaIntent {
    (response["posts"] as? JsonObject)?.let { posts ->
        return ChannelDeltaIntent.PostsBatch(posts)
    }
    (response["pending"] as? JsonObject)?.let { pending ->
        return ChannelDeltaIntent.PendingPost(pending)
    }
    val wrap = response["post"] as? JsonObject
        ?: return ChannelDeltaIntent.Unknown(response.keys)

    val rawId = wrap["id"].asStr()
        ?: return ChannelDeltaIntent.Unknown(setOf("post"))
    val id = rawId.replace(".", "")
    val rPost = wrap["r-post"] as? JsonObject
        ?: return ChannelDeltaIntent.Unknown(wrap.keys)

    (rPost["set"] as? JsonObject)?.let { set ->
        if (set["type"].asStr() == "tombstone") {
            return ChannelDeltaIntent.PostTombstone(id)
        }
        return ChannelDeltaIntent.PostSet(id, set)
    }
    if (rPost["set"] is JsonNull) {
        return ChannelDeltaIntent.PostDeleted(id)
    }
    (rPost["reacts"] as? JsonObject)?.let { reacts ->
        return ChannelDeltaIntent.PostReactions(id, reacts)
    }
    (rPost["essay"] as? JsonObject)?.let { essay ->
        return ChannelDeltaIntent.PostEssay(id, essay)
    }
    (rPost["reply"] as? JsonObject)?.let { reply ->
        return classifyReply(id, reply)
    }
    return ChannelDeltaIntent.Unknown(rPost.keys)
}

private fun classifyReply(parentId: String, reply: JsonObject): ChannelDeltaIntent {
    val rawReplyId = reply["id"].asStr()
        ?: return ChannelDeltaIntent.Unknown(reply.keys)
    val replyId = rawReplyId.replace(".", "")
    val rReply = reply["r-reply"] as? JsonObject
        ?: return ChannelDeltaIntent.Reply(
            parentId = parentId,
            replyId = replyId,
            inner = ReplyIntent.Unknown(reply.keys),
        )

    val inner: ReplyIntent = when {
        rReply["set"] is JsonNull -> ReplyIntent.Deleted
        rReply["set"] is JsonObject -> {
            val set = rReply["set"] as JsonObject
            if (set["type"].asStr() == "tombstone") {
                ReplyIntent.Tombstone
            } else {
                // Reply essays are nested under reply-essay in the set
                // payload (per tlon-apps wire model).
                val essay = set["reply-essay"] as? JsonObject
                if (essay != null) ReplyIntent.Upsert(essay)
                else ReplyIntent.Unknown(set.keys)
            }
        }
        rReply["reacts"] is JsonObject ->
            ReplyIntent.Reactions(rReply["reacts"] as JsonObject)
        else -> ReplyIntent.Unknown(rReply.keys)
    }
    return ChannelDeltaIntent.Reply(parentId, replyId, inner)
}
