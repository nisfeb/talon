package io.nisfeb.talon.urbit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Pure JSON shape builders for %chat, %channels, %groups pokes.
 *
 * Extracted from TlonChatRepo so tests can assert exact wire format
 * without constructing a repo. Shape bugs (wrong mark, missing field,
 * dejs mismatch) have historically been our biggest source of silent
 * breakage; snapshotting these shapes is how we catch regressions.
 */

/** %chat chat-dm-action-2 envelope. */
internal fun dmAction(peer: String, postId: String, delta: JsonObject): JsonObject =
    buildJsonObject {
        put("ship", peer)
        put("diff", buildJsonObject {
            put("id", postId)
            put("delta", delta)
        })
    }

/**
 * %chat chat-club-action-2 envelope. The `uid: "0v4"` is a throwaway
 * dedup token we inherit from Tlon's web client.
 */
internal fun clubAction(clubId: String, postId: String, delta: JsonObject): JsonObject =
    buildJsonObject {
        put("id", clubId)
        put("diff", buildJsonObject {
            put("uid", "0v4")
            put("delta", buildJsonObject {
                put("writ", buildJsonObject {
                    put("id", postId)
                    put("delta", delta)
                })
            })
        })
    }

/** %channels channel-action-2 outer envelope. */
internal fun channelAction(nest: String, action: JsonObject): JsonObject =
    buildJsonObject {
        put("channel", buildJsonObject {
            put("nest", nest)
            put("action", action)
        })
    }

/**
 * `action.order: [dottedPostId, …]` — set a channel's pinned/arranged
 * posts list. Tlon's pin feature is `order[0]`; pin by prepending the
 * post's dotted id, unpin by filtering it out. Pass an empty list to
 * clear.
 */
internal fun channelOrderAction(postIds: List<String>): JsonObject =
    buildJsonObject {
        put("order", buildJsonArray {
            postIds.forEach { add(JsonPrimitive(dotAtom(it))) }
        })
    }

/** Writs reply-add delta (DM / club). */
internal fun replyDelta(replyId: String, replyEssay: JsonObject): JsonObject =
    buildJsonObject {
        put("reply", buildJsonObject {
            put("id", replyId)
            put("meta", JsonNull)
            put("delta", buildJsonObject {
                put("add", buildJsonObject {
                    put("reply-essay", replyEssay)
                    put("time", JsonNull)
                })
            })
        })
    }

/** %groups group-action-4 `{group: {flag, a-group: <diff>}}` envelope. */
internal fun groupAction4(flag: String, aGroup: JsonObject): JsonObject =
    buildJsonObject {
        put("group", buildJsonObject {
            put("flag", flag)
            put("a-group", aGroup)
        })
    }

/**
 * Build a PostEssay. Caller supplies author — keeps this function
 * pure and testable. (Repo wraps with its own `ourPatp`.)
 */
internal fun buildEssay(
    content: JsonArray,
    author: String,
    sentMs: Long,
    kind: String = "/chat",
    meta: JsonObject? = null,
): JsonObject = buildJsonObject {
    put("content", content)
    put("author", author)
    put("sent", sentMs)
    put("kind", kind)
    put("meta", meta ?: JsonNull)
    put("blob", JsonNull)
}

/** `post.reply.{id, action: {add: replyEssay}}` — for %channels replies. */
internal fun channelReplyAdd(parentId: String, replyEssay: JsonObject): JsonObject =
    buildJsonObject {
        put("post", buildJsonObject {
            put("reply", buildJsonObject {
                put("id", dotAtom(parentId))
                put("action", buildJsonObject {
                    put("add", replyEssay)
                })
            })
        })
    }

/** `post.del: postId` for deleting a top-level channel post. */
internal fun channelPostDelete(postId: String): JsonObject =
    buildJsonObject {
        put("post", buildJsonObject {
            put("del", JsonPrimitive(dotAtom(postId)))
        })
    }

/** `post.reply.{id, action.del: postId}` for deleting a channel reply. */
internal fun channelReplyDelete(parentId: String, postId: String): JsonObject =
    buildJsonObject {
        put("post", buildJsonObject {
            put("reply", buildJsonObject {
                put("id", dotAtom(parentId))
                put("action", buildJsonObject {
                    put("del", JsonPrimitive(dotAtom(postId)))
                })
            })
        })
    }

/** `post.add-react: {id, author, react}`. */
internal fun channelAddReact(postId: String, author: String, emoji: String): JsonObject =
    buildJsonObject {
        put("post", buildJsonObject {
            put("add-react", buildJsonObject {
                put("id", dotAtom(postId))
                put("author", author)
                put("react", emoji)
            })
        })
    }

/** `post.del-react: {id, author}`. */
internal fun channelDelReact(postId: String, author: String): JsonObject =
    buildJsonObject {
        put("post", buildJsonObject {
            put("del-react", buildJsonObject {
                put("id", dotAtom(postId))
                put("author", author)
            })
        })
    }

/** Gallery link block: `{block: {link: {url, meta: {}}}}`. */
internal fun galleryLinkBlock(url: String): JsonObject =
    buildJsonObject {
        put("block", buildJsonObject {
            put("link", buildJsonObject {
                put("url", url)
                put("meta", buildJsonObject { })
            })
        })
    }

/** Notebook meta bag for an essay. */
internal fun notebookMeta(title: String, image: String): JsonObject =
    buildJsonObject {
        put("title", title)
        put("image", image)
        put("description", "")
        put("cover", "")
    }

// ─── %chat deltas ────────────────────────────────────────────────

/** Writs `add` delta for DM / club post. */
internal fun writsAddDelta(essay: JsonObject): JsonObject =
    buildJsonObject {
        put("add", buildJsonObject {
            put("essay", essay)
            put("time", JsonNull)
        })
    }

/** Writs `del` delta — used for both DM and club deletes. */
internal fun writsDelDelta(): JsonObject =
    buildJsonObject { put("del", JsonNull) }

/** Writs `add-react` delta: `{add-react: {author, react}}`. */
internal fun writsAddReactDelta(author: String, emoji: String): JsonObject =
    buildJsonObject {
        put("add-react", buildJsonObject {
            put("author", author)
            put("react", emoji)
        })
    }

/** Writs `del-react` delta: `{del-react: author}`. */
internal fun writsDelReactDelta(author: String): JsonObject =
    buildJsonObject { put("del-react", author) }

// ─── %groups lifecycle pokes (no a-group envelope) ───────────────

/** Body for `mark=group-join`. */
internal fun groupJoinPayload(flag: String): JsonObject =
    buildJsonObject {
        put("flag", flag)
        put("join-all", true)
    }

// ─── admin a-group branches ─────────────────────────────────────
//
// Every branch of `{group: {flag, a-group: {...}}}` we actually send.
// Snapshots here are the fastest way to catch a mark-4 → mark-5
// surface change — one failing test per renamed key.

/** a-group meta update. */
internal fun aGroupMetaUpdate(
    title: String, description: String, image: String, cover: String,
): JsonObject = buildJsonObject {
    put("meta", buildJsonObject {
        put("title", title)
        put("description", description)
        put("image", image)
        put("cover", cover)
    })
}

/** a-group delete. */
internal fun aGroupDelete(): JsonObject =
    buildJsonObject { put("delete", JsonNull) }

/** a-group seat diff — kick, role add / del. */
internal fun aGroupSeatDel(ship: String): JsonObject = buildJsonObject {
    put("seat", buildJsonObject {
        put("ships", buildJsonArray { add(JsonPrimitive(normalisePatp(ship))) })
        put("a-seat", buildJsonObject { put("del", JsonNull) })
    })
}

internal fun aGroupSeatAddRole(ship: String, role: String): JsonObject = buildJsonObject {
    put("seat", buildJsonObject {
        put("ships", buildJsonArray { add(JsonPrimitive(normalisePatp(ship))) })
        put("a-seat", buildJsonObject {
            put("add-roles", buildJsonArray { add(JsonPrimitive(role)) })
        })
    })
}

internal fun aGroupSeatDelRole(ship: String, role: String): JsonObject = buildJsonObject {
    put("seat", buildJsonObject {
        put("ships", buildJsonArray { add(JsonPrimitive(normalisePatp(ship))) })
        put("a-seat", buildJsonObject {
            put("del-roles", buildJsonArray { add(JsonPrimitive(role)) })
        })
    })
}

/** a-group entry.pending.del — revoke a direct invite. */
internal fun aGroupPendingDel(ship: String): JsonObject = buildJsonObject {
    put("entry", buildJsonObject {
        put("pending", buildJsonObject {
            put("ships", buildJsonArray { add(JsonPrimitive(normalisePatp(ship))) })
            put("a-pending", buildJsonObject { put("del", JsonNull) })
        })
    })
}

/** a-group entry.token.del — revoke a token-based invite. */
internal fun aGroupTokenDel(token: String): JsonObject = buildJsonObject {
    put("entry", buildJsonObject {
        put("token", buildJsonObject { put("del", token) })
    })
}

/** a-group entry.ask — approve / deny a join request. */
internal fun aGroupAskResolve(ship: String, approve: Boolean): JsonObject = buildJsonObject {
    put("entry", buildJsonObject {
        put("ask", buildJsonObject {
            put("ships", buildJsonArray { add(JsonPrimitive(normalisePatp(ship))) })
            put("a-ask", if (approve) "approve" else "deny")
        })
    })
}

/** a-group entry.ban.add-ships. */
internal fun aGroupBanAdd(ship: String): JsonObject = buildJsonObject {
    put("entry", buildJsonObject {
        put("ban", buildJsonObject {
            put("add-ships", buildJsonArray { add(JsonPrimitive(normalisePatp(ship))) })
        })
    })
}

/** a-group entry.ban.del-ships. */
internal fun aGroupBanDel(ship: String): JsonObject = buildJsonObject {
    put("entry", buildJsonObject {
        put("ban", buildJsonObject {
            put("del-ships", buildJsonArray { add(JsonPrimitive(normalisePatp(ship))) })
        })
    })
}

/** Top-level group-action-4 invite (sibling of `group`, not under a-group). */
internal fun groupAction4InviteAdd(flag: String, ship: String): JsonObject =
    buildJsonObject {
        put("invite", buildJsonObject {
            put("flag", flag)
            put("ships", buildJsonArray { add(JsonPrimitive(normalisePatp(ship))) })
            put("a-invite", buildJsonObject {
                put("token", JsonNull)
                put("note", JsonNull)
            })
        })
    }

/** Ensure a ship string carries the leading `~`. Idempotent. */
internal fun normalisePatp(ship: String): String =
    if (ship.startsWith("~")) ship else "~$ship"
