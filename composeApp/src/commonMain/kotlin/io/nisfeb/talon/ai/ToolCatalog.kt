package io.nisfeb.talon.ai

import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.urbit.StoryCache
import io.nisfeb.talon.urbit.TlonChatRepo
import io.nisfeb.talon.urbit.asInt
import io.nisfeb.talon.urbit.asText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
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

    /** [what] waits in the queue for a slow ship, said calmly ([io.nisfeb.talon.urbit.TlonChatRepo.sendConfirmed]'s check). */
    fun queued(what: String): String = "Queued $what: your ship is slow; it goes when it answers."

    fun default(
        repo: TlonChatRepo,
        db: AppDatabase,
        // Nullable: a platform without a working on-device embedder
        // (e.g. a desktop host the EmbedderProbe failed) still gets the
        // assistant — search_history falls back to keyword-only.
        embedder: SearchEmbedderClient?,
        // When non-null, the web_search tool is added. Callers gate this on
        // a Brave key being set; the client also no-ops with a message when
        // the assistant is off.
        braveSearch: BraveSearchClient? = null,
        // When non-null, the fetch_url tool is added — no key needed; the
        // fetcher self-checks the assistant is on and hardens against
        // internal/SSRF targets.
        urlFetcher: UrlFetcher? = null,
        // When non-null, the find_places tool is added: the vendor's place
        // search. Apart from braveSearch so a dictation can have places
        // without the web.
        places: BraveSearchClient? = null,
        // When non-null, open_directions is added: it hands a maps link to
        // the device and says whether it went. Only where someone is
        // looking at the screen, so not a scheduled loop.
        open: ((uri: String) -> Boolean)? = null,
        // With [open], call_place too: a phone has a dialer, a computer
        // does not (isPhoneDialSupported).
        dials: Boolean = false,
        // What search_history, read_conversation and list_unread showed: a dictation's card draws them.
        onMessages: (List<MessageEntity>) -> Unit = {},
        // A conversation's name as the chat list gives it (ContactMap.conversationLabel).
        conversationLabel: (whom: String) -> String = { it },
        displayName: (String) -> String,
    ): List<Tool> = listOfNotNull(
        Tool(
            spec = ToolSpec(
                "search_history",
                "Search the user's whole chat history (semantic + keyword). Returns the most relevant messages with their whom (conversation id), post (message id), a link that opens the message, author, and text.",
                toolSchema(
                    "query" to ("string" to "What to search for, in natural language."),
                    "k" to ("integer" to "Max results (default 10)."),
                    required = listOf("query"),
                ),
            ),
            write = false,
        ) { args ->
            val q = args.str("query").orEmpty()
            if (q.isBlank()) return@Tool "Error: query is required."
            // Clamp: a model-supplied negative k would throw; 0 would
            // silently return nothing. Mirrors read_conversation.
            val k = (args.int("k") ?: 10).coerceIn(1, 50)
            // Hybrid (semantic + keyword) when an embedder is available;
            // keyword-only otherwise so the tool still works on a host
            // with no on-device embedder.
            val hits = embedder?.hybridSearch(q, k)
                ?: keywordSearch(db, AskUrbit.salientTerms(q)).take(k)
            onMessages(hits)
            format(hits, displayName)
        },
        Tool(
            spec = ToolSpec(
                "read_conversation",
                "Read the most recent messages in one conversation, oldest-first.",
                toolSchema(
                    "whom" to ("string" to "Conversation id from a prior tool result."),
                    "count" to ("integer" to "How many recent messages (default 30)."),
                    required = listOf("whom"),
                ),
            ),
            write = false,
        ) { args ->
            val whom = args.str("whom") ?: return@Tool "Error: whom is required."
            val count = (args.int("count") ?: 30).coerceIn(1, 100)
            val read = db.messages().latestFor(whom, count).asReversed()
            onMessages(read)
            format(read, displayName)
        },
        // "Read me my unread messages", "What did Andrea say?" (sneagan,
        // 2026-10-16). Read-only: hearing them leaves them unread.
        Tool(
            spec = ToolSpec(
                "list_unread",
                "The user's unread chat messages, as the chat list counts them: a paragraph for each conversation with any, newest first, " +
                    "its name and then its unread messages oldest first, and last the conversations' whom ids. " +
                    "Give from for one person's only (their ship, from find_person); with nothing unread from them, read_conversation with whom = their ship gives what they said last.",
                toolSchema(
                    "from" to ("string" to "Only messages from this ship."),
                    "count" to ("integer" to "Most messages per conversation (default 10, max 30)."),
                    "aloud" to ("boolean" to "true when the owner asked to hear them: they are read out word for word, so say only a short line after."),
                    required = emptyList(),
                ),
            ),
            write = false,
        ) { args ->
            val from = args.str("from")?.trim()?.takeIf { it.isNotEmpty() }?.let { "~" + it.removePrefix("~") }
            val per = (args.int("count") ?: 10).coerceIn(1, 30)
            val found = Briefing.unread(db, repo.ownShip, per, from).take(MAX_UNREAD_CONVERSATIONS)
            onMessages(found.flatMap { it.second })
            if (found.isEmpty()) return@Tool if (from == null) "Nothing unread." else "Nothing unread from ${displayName(from)}."
            found.joinToString("\n\n") { (whom, msgs) ->
                val dm = whom.startsWith("~")
                (listOf(if (dm) "${displayName(whom)}:" else "In ${conversationLabel(whom)}:") + msgs.map { m ->
                    // A sentence each, so a run of them is not read as one.
                    val text = StoryCache.textFor(m.id, m.contentJson).replace('\n', ' ').trim().take(500)
                        .let { if (it.isEmpty() || it.last() in ".!?…") it else "$it." }
                    if (dm) text else "${displayName(m.author)}: $text"
                }).joinToString("\n")
            } + "\n\n${Briefing.UNREAD_IDS} " + found.joinToString("; ") { (whom, _) -> "${conversationLabel(whom)} = $whom" }
        },
        Tool(
            spec = ToolSpec(
                "send_message",
                "Send a new message to a conversation.",
                toolSchema(
                    "whom" to ("string" to "Conversation id from a prior tool result."),
                    "text" to ("string" to "The message body."),
                    required = listOf("whom", "text"),
                ),
            ),
            write = true,
        ) { args ->
            val whom = args.str("whom") ?: return@Tool "Error: whom is required."
            val text = args.str("text")?.takeIf { it.isNotBlank() }
                ?: return@Tool "Error: text is required."
            repo.send(whom, text)
            // A slow ship's send returns with the message queued: "Sent." was said of one still waiting.
            if (db.messages().queuedIn(whom) > 0) queued("for ${conversationLabel(whom)}") else "Sent to ${conversationLabel(whom)}."
        },
        Tool(
            spec = ToolSpec(
                "reply",
                "Reply in-thread to a specific message.",
                toolSchema(
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
            val text = args.str("text")?.takeIf { it.isNotBlank() }
                ?: return@Tool "Error: text is required."
            repo.reply(whom, parent, text)
            "Replied."
        },
        Tool(
            spec = ToolSpec(
                "react",
                "Add an emoji reaction to a message.",
                toolSchema(
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
            val emoji = args.str("emoji")?.takeIf { it.isNotBlank() }
                ?: return@Tool "Error: emoji is required."
            repo.react(whom, post, emoji)
            "Reacted."
        },
        Tool(
            spec = ToolSpec(
                "mark_read",
                "Mark a conversation as read.",
                toolSchema(
                    "whom" to ("string" to "Conversation id."),
                    required = listOf("whom"),
                ),
            ),
            write = true,
        ) { args ->
            val whom = args.str("whom") ?: return@Tool "Error: whom is required."
            repo.markRead(whom, force = true)
            "Marked read."
        },
        // Web search — only present when the caller passes a client (the
        // user opted in). Read-only: it fetches, it doesn't mutate.
        braveSearch?.let { brave ->
            Tool(
                spec = ToolSpec(
                    "web_search",
                    "Search the public web (Brave Search) for current events, facts, or anything outside the user's chat history. Returns ranked results with title, URL, and snippet.",
                    toolSchema(
                        "query" to ("string" to "The search query, in natural language."),
                        "count" to ("integer" to "Max results (default 5, max 20)."),
                        required = listOf("query"),
                    ),
                ),
                write = false,
            ) { args ->
                val q = args.str("query").orEmpty()
                if (q.isBlank()) return@Tool "Error: query is required."
                val count = (args.int("count") ?: 5).coerceIn(1, 20)
                brave.search(q, count)
            }
        },
        // Places near somewhere, nearest first (sneagan, 2026-10-17: "what is
        // the closest home Depot to nutcracker rehearsal"). Read-only.
        places?.let { client ->
            Tool(
                spec = ToolSpec(
                    "find_places",
                    "Find places near somewhere, nearest first by straight-line distance, with each one's address, today's hours and phone: " +
                        "a shop or service by name (Home Depot) or by kind (hardware store, pharmacy). " +
                        "To search near a calendar event, first read its location with list_events and pass that as near. " +
                        "For errands, read list_tasks, turn each errand into the kind of place that does it (paint: hardware store; a prescription: pharmacy), and search them all in one call. " +
                        "The answer says what near was found as; say so if it is not the place meant.",
                    toolSchema(
                        "query" to ("string" to "What to find: a name or a kind of place. Several, separated by ;, are each searched near the same place."),
                        "near" to ("string" to "Where to search from: an address, a place's name, or lat,lon."),
                        "area" to ("string" to "The city, state and country near is in, when known, e.g. jacksonville fl united states, so a name is found in the right town."),
                        "count" to ("integer" to "How many, nearest first (default 3, max 10)."),
                        required = listOf("query", "near"),
                    ),
                ),
                write = false,
            ) { args ->
                client.places(args.str("query").orEmpty(), args.str("near").orEmpty(), args.str("area"), (args.int("count") ?: 3))
            }
        },
        // Hand-offs to the device, for "take me there" and "call it": they
        // open an app on the screen and change nothing, so are not writes.
        open?.let { opener ->
            Tool(
                spec = ToolSpec(
                    "open_directions",
                    "Open the device's maps app with directions from where the owner is to a place, ready to start. Give the address find_places, list_events or orrery gave, with its town.",
                    toolSchema(
                        "destination" to ("string" to "The address, or a place's name and town."),
                        "mode" to ("string" to "walking, driving, bicycling or transit, where the owner said how or it is plain; else leave it out and the maps app chooses."),
                        required = listOf("destination"),
                    ),
                ),
                write = false,
            ) { args ->
                val to = args.str("destination")?.trim()?.takeIf { it.isNotEmpty() } ?: return@Tool "Error: destination is required."
                if (opener(io.nisfeb.talon.ui.mapsDirectionsUri(to, args.str("mode")))) "Opened directions to $to." else "Couldn't open a maps app."
            }
        },
        open?.takeIf { dials }?.let { opener ->
            Tool(
                spec = ToolSpec(
                    "call_place",
                    "Open the phone's dialer with a number, such as the phone find_places gave. It does not call: the user taps call.",
                    toolSchema("number" to ("string" to "The phone number."), required = listOf("number")),
                ),
                write = false,
            ) { args ->
                val number = args.str("number")?.trim().orEmpty()
                if (number.count { it.isDigit() } < 7) return@Tool "Error: \"$number\" is not a phone number."
                if (opener(io.nisfeb.talon.ui.telUri(number))) "Opened the dialer with $number. The call starts when the user taps call."
                else "Couldn't open the dialer."
            }
        },
        // Fetch a specific URL — only present when the caller opts in.
        // Read-only: it GETs and returns text, it doesn't mutate.
        urlFetcher?.let { fetcher ->
            Tool(
                spec = ToolSpec(
                    "fetch_url",
                    "Fetch a single public web page or API endpoint (http/https) and return its text content (HTML is reduced to text). Use to read a specific web_search result, or a known URL/JSON API directly — e.g. https://hacker-news.firebaseio.com/v0/topstories.json. Internal/loopback addresses are refused.",
                    toolSchema(
                        "url" to ("string" to "The full http(s) URL to fetch."),
                        required = listOf("url"),
                    ),
                ),
                write = false,
            ) { args ->
                val url = args.str("url")?.takeIf { it.isNotBlank() }
                    ?: return@Tool "Error: url is required."
                fetcher.fetch(url)
            }
        },
    )

    /** How many conversations list_unread gives at most. */
    private const val MAX_UNREAD_CONVERSATIONS = 20

    // ───────── helpers ─────────

    private fun format(messages: List<MessageEntity>, displayName: (String) -> String): String {
        if (messages.isEmpty()) return "No messages found."
        return messages.joinToString("\n") { m ->
            val text = StoryCache.copyTextFor(m.id, m.contentJson).replace('\n', ' ').take(300)
            "whom=${m.whom} post=${m.id} link=${io.nisfeb.talon.urbit.TalonLink.forMessage(m.whom, m.id, m.parentId)} from=${displayName(m.author)}: $text"
        }
    }
}

// The model writes these args. `.jsonPrimitive` throws when it hands us an
// object or array where a string belongs; the shared accessors degrade to
// null, and every call site below already handles a missing arg.
private fun JsonObject.str(key: String): String? = this[key].asText()
private fun JsonObject.int(key: String): Int? = this[key].asInt()

/** Build a JSON-Schema object for a tool's arguments. The ONE helper for
 *  every catalog (ToolCatalog, AssistantActions) — a second copy once
 *  drifted from this one. */
internal fun toolSchema(
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
