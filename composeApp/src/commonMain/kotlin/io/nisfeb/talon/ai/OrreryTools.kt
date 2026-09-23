package io.nisfeb.talon.ai

import io.nisfeb.talon.orrery.OrreryApi
import io.nisfeb.talon.orrery.OrreryRepo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Orrery, as something the owner can talk to.
 *
 * Deliberately general. Orrery's own routes are a handful of documents
 * and one fact-writing route, so these tools are that shape and know
 * nothing about what any one document is for: setting up the Telegram
 * reader is writing `telegram`, and this file has never heard of
 * Telegram. A setting orrery grows next month needs nothing here.
 *
 * The long rules are not in the system prompt. A general question
 * about Urbit should not pay for them, so they sit behind
 * [orreryGuide] and arrive only once somebody is actually doing this.
 */

/** What the model is told before it writes anything to orrery. */
internal const val ORRERY_GUIDE = """
Orrery is the owner's own model of their life, kept on their ship. It
holds bodies — person/, place/, activity/, situation/, thing/ — and
facts about them. You are talking to it as the owner.

Before you write:

- Ask the ship what it knows. orrery_find resolves a name to a body id;
  a hit IS the thing, so never create a second body for a name that
  already resolves. orrery_read with no body gives the whole view,
  including the schema: which kinds exist, which attributes each kind
  takes, and what the ship says each one means.
- A fact is one subject, one attribute, one value. `at` is when the
  thing happened or will happen, not when you are writing it.
- A schedule is not a fact about the past. An activity's `next` and a
  situation's `starts` are the plan; `started` and `ended` are written
  only once they have.
- A status is a circumstance, not a feeling. "on jury duty" is a
  status; "fed up" is not, and does not go on one.
- An activity's `status` is the whole series: `active`, or `cancelled`
  only where the owner says the series itself is over. One meeting
  being called off is not that and has no field yet.
- health and income are kept from most keys. If the ship refuses one,
  that is its answer, not a problem to work around.

Settings live in documents, read and written whole by name:

- `generator`: the on-ship model. Its OpenRouter key is what every
  other on-ship reader borrows, so a blank key means the ship reads
  nothing, whatever else is switched on.
- `telegram`: the Telegram reader. Its token comes from BotFather, its
  secret is the owner's own invention and must be 16 bytes or longer,
  and `public_url` is where the ship is reachable from the internet,
  because Telegram pushes to it. `chats` is the chat ids it may read,
  `people` maps a Telegram user id to a body id. Once the three are
  set, orrery_register telegram: the ship asks Telegram to push
  updates to it and reads back what Telegram holds. A 200 is not
  delivery: report the url held, pending_update_count and
  last_error_message. A last_error_message naming TLS or DNS means
  the public URL is not reachable from the internet, which is the
  owner's to fix.
- `chat`: the ship's own reader of the owner's Tlon chats. `enabled`,
  `dms` (whom strings: a ~ship, or a group DM id like 0v4.abcde),
  `channels` (nests like chat/~host/general), `people` (a ship to a
  body id, needed only for a person with no body carrying that ship),
  `read_own`, `poll_minutes`, `backfill_hours`, `gate` and `escalate`
  (0 to 100), `max_daily_messages` and `model`. A list you give
  replaces the list whole. What the ship holds to pick from is
  orrery_settings chat/dms and chat/channels, each item an id and a
  name: pick by the name, since many channel ids are random strings.
  Its last pass is orrery_settings chat/last. It borrows the
  generator's key, so that is set first. It reads only the DMs and
  channels listed, and only from people it knows by ship (a person
  body with its ship, or one in `people`). It is the only reader of
  the owner's chats: Talon reads none itself. Talon goes on reading
  calls and mail. The calendar's events the ship also writes itself.
- `schema`, `policy`: what bodies may carry, and what the ship does
  with what it is told.

A fresh setup goes: the generator's key; `telegram`'s three fields,
then orrery_register telegram; then `chat` with the DMs and channels
the owner picked and enabled true.

Writing a document merges: a field you leave out keeps its value, a
field you send as null is cleared so the ship's default stands, and a
credential you leave blank ("") keeps the stored one. The ship answers
a write once it has landed, with the document as stored: that answer
is the settings now, so there is no need to read it again. Credentials
come back masked, never as themselves, so you cannot show the owner a
token they have already set, and you should not ask them to repeat one
to confirm it.
"""

/**
 * What the tools need of orrery, which is less than orrery is.
 *
 * Here so the tools can be held to their own behaviour — a document
 * name refused, a malformed batch refused, the ship's answer read
 * back — without a ship, a database and a key to do it with.
 */
interface OrreryTap {
    /** Where this install reaches the owner's ship; each owner's is their own. */
    val shipUrl: String? get() = null
    suspend fun find(q: String): Result<List<Pair<String, String>>>
    suspend fun state(): Result<String>
    suspend fun body(id: String): Result<List<String>>
    suspend fun observe(batch: JsonObject): Result<List<String>>
    suspend fun settings(name: String): Result<String>
    suspend fun configure(name: String, body: JsonObject): Result<String>
    suspend fun register(name: String): Result<String>
    suspend fun registration(name: String): Result<String>
}

/** The repo as the tools see it. */
fun OrreryRepo.asTap(): OrreryTap = object : OrreryTap {
    override val shipUrl get() = this@asTap.shipUrl

    override suspend fun find(q: String) =
        resolveBody(q).map { hits -> hits.map { it.id to "kind=${it.kind} name=${it.name} matched=${it.match}" } }

    override suspend fun state() = readState().map { it.toString() }

    override suspend fun body(id: String) = bodyTimeline(id).map { rows ->
        rows.map { "obs=${it.id} attr=${it.attr} at=${it.atMs} source=${it.sourceId} ${if (it.stands) "stands" else it.status}" }
    }

    override suspend fun observe(batch: JsonObject) = observeNow(batch).map { answer ->
        answer.observations.mapIndexed { i, it ->
            when {
                it.ok && it.existing -> "${i + 1}: already known"
                it.ok -> "${i + 1}: written"
                else -> "${i + 1}: refused, ${it.error ?: "no reason given"}"
            }
        }
    }

    override suspend fun settings(name: String) = readSettings(name)

    override suspend fun configure(name: String, body: JsonObject) = writeSettings(name, body)

    override suspend fun register(name: String) = this@asTap.register(name)

    override suspend fun registration(name: String) = readRegistration(name)
}

/** Orrery's tools, where this install is attached to a ship that has it. */
fun orreryTools(repo: OrreryRepo): List<Tool> = orreryTools(repo.asTap())

fun orreryTools(orrery: OrreryTap): List<Tool> = buildList {
    add(Tool(
        spec = ToolSpec(
            "orrery_guide",
            "How orrery works and what its rules are. Call this once before writing facts or settings to orrery; it is short and it says what the other orrery tools expect.",
            toolSchema(required = emptyList()),
        ),
        write = false,
    ) {
        ORRERY_GUIDE.trim() + (orrery.shipUrl?.let {
            "\n\nThis install reaches the owner's ship at $it. That is usually the `public_url` Telegram needs; " +
                "confirm it with the owner, since it must be reachable from the internet."
        } ?: "")
    })

    add(Tool(
        spec = ToolSpec(
            "orrery_find",
            "Ask orrery which body a name means, before writing anything about it. A hit is the thing itself: use its id rather than making a second body.",
            toolSchema("name" to ("string" to "The name as the owner said it."), required = listOf("name")),
        ),
        write = false,
    ) { args ->
        val q = args.str("name") ?: return@Tool "Error: name is required."
        orrery.find(q).fold(
            onSuccess = { hits ->
                if (hits.isEmpty()) "Nothing in orrery matches \"$q\"."
                else hits.take(10).joinToString("\n") { (id, rest) -> "id=$id $rest" }
            },
            onFailure = { "Could not ask orrery: ${it.message}" },
        )
    })

    add(Tool(
        spec = ToolSpec(
            "orrery_read",
            "Read orrery. With no argument: every body the key may see, the attributes each kind takes and what the ship says they mean. With body: that body's timeline, what was said about it and whether each row still stands.",
            toolSchema("body" to ("string" to "A body id, e.g. person/alice. Omit for the whole view."), required = emptyList()),
        ),
        write = false,
    ) { args ->
        val id = args.str("body")
        if (id == null) {
            orrery.state().fold(
                onSuccess = { clip(it) },
                onFailure = { "Could not read orrery: ${it.message}" },
            )
        } else {
            orrery.body(id).fold(
                onSuccess = { rows ->
                    if (rows.isEmpty()) "orrery holds nothing about $id." else rows.take(60).joinToString("\n")
                },
                onFailure = { "Could not read $id: ${it.message}" },
            )
        }
    })

    add(Tool(
        spec = ToolSpec(
            "orrery_observe",
            "Write facts to orrery under this install's key. Give a JSON array of observations, each {\"subject\": body id, \"attr\": attribute, \"value\": the value, \"at\": ISO 8601 UTC when it happened}. Resolve names with orrery_find first; the ship answers per item and may refuse one.",
            toolSchema(
                "observations" to ("string" to "A JSON array of observation objects."),
                required = listOf("observations"),
            ),
        ),
        write = true,
    ) { args ->
        val raw = args.str("observations") ?: return@Tool "Error: observations is required."
        val arr = runCatching { Json.parseToJsonElement(raw) as? JsonArray }.getOrNull()
            ?: return@Tool "Error: observations must be a JSON array."
        if (arr.isEmpty()) return@Tool "Error: nothing to write."
        val batch = buildJsonObject { put("observations", arr) }
        orrery.observe(batch).fold(
            onSuccess = { said -> if (said.isEmpty()) "The ship answered nothing." else said.joinToString("\n") },
            onFailure = { "Could not write to orrery: ${it.message}" },
        )
    })

    add(Tool(
        spec = ToolSpec(
            "orrery_settings",
            "Read one of orrery's settings documents: " + OrreryApi.SETTINGS.sorted().joinToString(", ") +
                ". Credentials come back only as whether they are set, never as themselves. Also " +
                OrreryApi.LISTS.sorted().joinToString(", ") + ": what the ship holds to pick from, read only.",
            toolSchema("document" to ("string" to "The document name."), required = listOf("document")),
        ),
        write = false,
    ) { args ->
        val name = args.str("document") ?: return@Tool "Error: document is required."
        if (name !in OrreryApi.SETTINGS && name !in OrreryApi.LISTS) return@Tool unknownDoc(name)
        orrery.settings(name).fold(
            onSuccess = { clip(it) },
            onFailure = { "Could not read $name: ${it.message}" },
        )
    })

    add(Tool(
        spec = ToolSpec(
            "orrery_configure",
            "Change one of orrery's settings documents: " + OrreryApi.SETTINGS.sorted().joinToString(", ") +
                ". Give only the fields to change: the ship keeps the rest, clears a field given as null, and keeps a stored credential given as \"\". It answers with the document as stored. Read orrery_guide first: some fields have rules the ship enforces and will refuse.",
            toolSchema(
                "document" to ("string" to "The document name."),
                "settings" to ("string" to "A JSON object of the fields to change."),
                required = listOf("document", "settings"),
            ),
        ),
        write = true,
    ) { args ->
        val name = args.str("document") ?: return@Tool "Error: document is required."
        if (name !in OrreryApi.SETTINGS) return@Tool unknownDoc(name)
        val raw = args.str("settings") ?: return@Tool "Error: settings is required."
        val obj = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return@Tool "Error: settings must be a JSON object."
        if (obj.isEmpty()) return@Tool "Error: nothing to change."
        orrery.configure(name, obj).fold(
            onSuccess = { "Written to $name. It now holds: " + clip(it) },
            onFailure = { "Could not write $name: ${it.message}" },
        )
    })

    add(Tool(
        spec = ToolSpec(
            "orrery_register",
            "Register one of orrery's settings documents with the outside service it names, then read back what that service holds: " +
                OrreryApi.REGISTRATIONS.keys.sorted().joinToString(", ") +
                ". The ship makes the call itself with the credentials it already holds. Read orrery_guide first: the ship refuses until the fields it needs are set.",
            toolSchema("document" to ("string" to "The document name."), required = listOf("document")),
        ),
        write = true,
    ) { args ->
        val name = args.str("document") ?: return@Tool "Error: document is required."
        if (name !in OrreryApi.REGISTRATIONS) {
            return@Tool "There is no orrery registration for \"$name\". It is one of: " +
                OrreryApi.REGISTRATIONS.keys.sorted().joinToString(", ") + "."
        }
        val said = orrery.register(name).fold(
            onSuccess = { "Registered $name. The ship says: " + clip(it) },
            onFailure = { return@Tool "Could not register $name: ${it.message}" },
        )
        orrery.registration(name).fold(
            onSuccess = { "$said\nWhat the service holds now: " + clip(it) },
            onFailure = { "$said\nCould not read back what the service holds: ${it.message}" },
        )
    })
}

private fun unknownDoc(name: String) =
    "There is no orrery settings document called \"$name\". It is one of: " +
        OrreryApi.SETTINGS.sorted().joinToString(", ") + "."

/** Enough of an answer to work from; the whole state view is long. */
private fun clip(s: String, max: Int = 6000): String =
    if (s.length <= max) s else s.take(max) + "\n… cut here; ask for one body instead."

private fun JsonObject.str(key: String): String? =
    this[key]?.let { (it as? JsonPrimitive)?.contentOrNull }?.takeIf { it.isNotBlank() }
