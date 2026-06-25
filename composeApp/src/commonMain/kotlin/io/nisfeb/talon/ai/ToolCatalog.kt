package io.nisfeb.talon.ai

import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.urbit.StoryCache
import io.nisfeb.talon.urbit.TlonChatRepo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * A tool the agent can call: its advertised [spec], whether it mutates
 * state ([write] → must be confirmed by the user, see [AgentLoop]), and
 * the [execute] that runs it.
 */
class Tool(
    val spec: ToolSpec,
    val write: Boolean,
    val execute: suspend (JsonObject) -> String,
)

/**
 * The default agent toolbox, wired onto the real [TlonChatRepo] pokes
 * and the local DB. Reads run freely; writes are gated. Adding a tool is
 * a single entry here — the group-admin suite (invite/kick/ban/role),
 * delete, edit, pin, and contact tools follow the same shape and are
 * intentionally left out of the first cut; add them as the UX for
 * elevated confirmations firms up.
 */
object ToolCatalog {

    fun default(
        repo: TlonChatRepo,
        db: AppDatabase,
        embedder: SearchEmbedderClient,
        displayName: (String) -> String,
    ): List<Tool> = listOf(
        Tool(
            spec = ToolSpec(
                "search_history",
                "Semantic search over the user's whole chat history. Returns the most relevant messages with their whom (conversation id), post (message id), author, and text.",
                schema(
                    "query" to ("string" to "What to search for, in natural language."),
                    "k" to ("integer" to "Max results (default 10)."),
                    required = listOf("query"),
                ),
            ),
            write = false,
        ) { args ->
            val q = args.str("query").orEmpty()
            if (q.isBlank()) return@Tool "Error: query is required."
            val k = args.int("k") ?: 10
            format(embedder.semanticSearch(q).take(k), displayName)
        },
        Tool(
            spec = ToolSpec(
                "read_conversation",
                "Read the most recent messages in one conversation, oldest-first.",
                schema(
                    "whom" to ("string" to "Conversation id from a prior tool result."),
                    "count" to ("integer" to "How many recent messages (default 30)."),
                    required = listOf("whom"),
                ),
            ),
            write = false,
        ) { args ->
            val whom = args.str("whom") ?: return@Tool "Error: whom is required."
            val count = (args.int("count") ?: 30).coerceIn(1, 100)
            format(db.messages().latestFor(whom, count).asReversed(), displayName)
        },
        Tool(
            spec = ToolSpec(
                "send_message",
                "Send a new message to a conversation.",
                schema(
                    "whom" to ("string" to "Conversation id from a prior tool result."),
                    "text" to ("string" to "The message body."),
                    required = listOf("whom", "text"),
                ),
            ),
            write = true,
        ) { args ->
            val whom = args.str("whom") ?: return@Tool "Error: whom is required."
            val text = args.str("text") ?: return@Tool "Error: text is required."
            repo.send(whom, text)
            "Sent."
        },
        Tool(
            spec = ToolSpec(
                "reply",
                "Reply in-thread to a specific message.",
                schema(
                    "whom" to ("string" to "Conversation id."),
                    "parentPost" to ("string" to "The post id being replied to."),
                    "text" to ("string" to "The reply body."),
                    required = listOf("whom", "parentPost", "text"),
                ),
            ),
            write = true,
        ) { args ->
            val whom = args.str("whom") ?: return@Tool "Error: whom is required."
            val parent = args.str("parentPost") ?: return@Tool "Error: parentPost is required."
            val text = args.str("text") ?: return@Tool "Error: text is required."
            repo.reply(whom, parent, text)
            "Replied."
        },
        Tool(
            spec = ToolSpec(
                "react",
                "Add an emoji reaction to a message.",
                schema(
                    "whom" to ("string" to "Conversation id."),
                    "post" to ("string" to "The post id to react to."),
                    "emoji" to ("string" to "A single unicode emoji, e.g. 👍."),
                    required = listOf("whom", "post", "emoji"),
                ),
            ),
            write = true,
        ) { args ->
            val whom = args.str("whom") ?: return@Tool "Error: whom is required."
            val post = args.str("post") ?: return@Tool "Error: post is required."
            val emoji = args.str("emoji") ?: return@Tool "Error: emoji is required."
            repo.react(whom, post, emoji)
            "Reacted."
        },
        Tool(
            spec = ToolSpec(
                "mark_read",
                "Mark a conversation as read.",
                schema(
                    "whom" to ("string" to "Conversation id."),
                    required = listOf("whom"),
                ),
            ),
            write = true,
        ) { args ->
            val whom = args.str("whom") ?: return@Tool "Error: whom is required."
            repo.markRead(whom)
            "Marked read."
        },
    )

    // ───────── helpers ─────────

    private fun format(messages: List<MessageEntity>, displayName: (String) -> String): String {
        if (messages.isEmpty()) return "No messages found."
        return messages.joinToString("\n") { m ->
            val text = StoryCache.textFor(m.id, m.contentJson).replace('\n', ' ').take(300)
            "whom=${m.whom} post=${m.id} from=${displayName(m.author)}: $text"
        }
    }
}

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

/** Build a JSON-Schema object for a tool's arguments. */
private fun schema(
    vararg props: Pair<String, Pair<String, String>>,
    required: List<String>,
): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
        props.forEach { (name, typeDesc) ->
            put(name, buildJsonObject {
                put("type", typeDesc.first)
                put("description", typeDesc.second)
            })
        }
    })
    putJsonArray("required") { required.forEach { add(it) } }
}
