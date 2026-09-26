package io.nisfeb.talon.ai

import io.nisfeb.talon.orrery.OrreryApi
import io.nisfeb.talon.orrery.OrreryPreferences
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
holds bodies (person/, place/, activity/, situation/, thing/, org/,
note/) and facts about them. You are talking to it as the owner.

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
- Something the owner hands you to keep (a list of todos for an event
  with its details, notes, a pasted page) goes to orrery_file_text
  whole, not fact by fact: the ship's reader files it and proposes the
  actions it implies for the owner to approve.
- A standing rule from the owner ("never propose calls before 9",
  "write briefly") is a preference: orrery_set_preferences. Every
  prompt on the ship reads them.

Settings live in documents, read and written whole by name:

- `generator`: the on-ship model. Its OpenRouter key is what every
  other on-ship reader borrows, so a blank key means the ship reads
  nothing, whatever else is switched on.
- `telegram`: the Telegram reader. Its token comes from BotFather, its
  secret is the owner's own invention and must be 16 bytes or longer,
  and `public_url` is the ship's base HTTPS address as the internet
  reaches it, with no path, because Telegram pushes to it. `enabled`
  must be true, or the ship drops every update it is sent. It reads a
  message only from a chat id listed in `chats` and a sender whose
  Telegram user id is in `people` (mapped to a body id); anything else
  is dropped. Once token, secret and public_url are set,
  orrery_register telegram: the ship asks Telegram to push updates to
  it and reads back what Telegram holds. A 200 is not delivery: report
  the url held, pending_update_count and last_error_message. A
  last_error_message naming TLS or DNS means the public URL is not
  reachable from the internet, which is the owner's to fix. To learn
  the ids for `chats` and `people`, have the owner message the bot,
  then read orrery_settings telegram/last: its `chat` and `from` are
  the last update's ids, and `outcome` says why it was dropped.
- `chat`: the ship's own reader of the owner's Tlon chats. `enabled`,
  `dms` (whom strings: a ~ship, or a group DM id like 0v4.abcde),
  `channels` (nests like chat/~host/general), `people` (a ship to a
  body id, needed only for a person with no body carrying that ship),
  `read_own`, `poll_minutes`, `backfill_hours`, `gate` and `escalate`
  (0 to 100), `max_daily_messages` and `model`. A list you give
  replaces the list whole. `poll_minutes` is 1 to 1440 and
  `backfill_hours` at most 720; the ship clamps what is outside. What
  the ship holds to pick from is orrery_settings chat/dms and
  chat/channels, each item an id and a name: pick by the name, since
  many channel ids are random strings, and by the id where the name is
  blank.
  Its last pass is orrery_settings chat/last. It borrows the
  generator's key, so that is set first. It reads only the DMs and
  channels listed, and only from people it knows by ship (a person
  body with its ship, or one in `people`). It is the only reader of
  the owner's chats: Talon reads none itself. Talon goes on reading
  calls and mail. The calendar's events the ship also writes itself.
- `schema`, `policy`: what bodies may carry, and what the ship does
  with what it is told.

A fresh setup goes: the generator's key; `telegram`'s token, secret,
public_url and enabled true, then orrery_register telegram, then its
`chats` and `people` from telegram/last once the owner has messaged
the bot; then `chat` with the DMs and channels the owner picked and
enabled true.

Writing `generator`, `telegram` or `chat` merges: a field you leave
out keeps its value, a field you send as null is cleared so the ship's
default stands, and a credential you leave blank ("") keeps the stored
one. `schema` and `policy` are replaced whole: read the document,
change it, and send all of it, or what you left out is gone. The ship
answers a write with the document as stored, once the write has landed
or it has stopped waiting: check your change is in it. Credentials
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
    /** The owner's style and standing preferences, as the ship holds them now. */
    suspend fun preferences(): Result<OrreryPreferences>
    /** Style set where given ("" clears it); [add] put on and [remove] taken off the list as the ship holds it now. */
    suspend fun changePreferences(add: String?, remove: String?, style: String?): Result<OrreryPreferences>
    /** Text for the ship to read and file; its answer, `{ok, id}` or `{ok, dropped}`. */
    suspend fun hand(text: String, title: String?): Result<JsonObject>
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

    override suspend fun preferences() = runCatching {
        if (!loadPreferences()) error("the ship did not say; an orrery older than preferences has none")
        this@asTap.preferences.value ?: error("the ship did not say")
    }

    override suspend fun changePreferences(add: String?, remove: String?, style: String?) = runCatching {
        if (style != null) setStyle(style).getOrThrow()
        if (add != null || remove != null) {
            this@asTap.changePreferences { list ->
                list.filterNot { it == remove }.let { if (add != null && add !in it) it + add else it }
            }.getOrThrow()
        }
        this@asTap.preferences.value ?: error("the ship did not say what it now holds")
    }

    override suspend fun hand(text: String, title: String?) = this@asTap.hand(text, title)
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
        ORRERY_GUIDE.trim() + (orrery.shipUrl?.let { "\n\n" + shipUrlHint(it) } ?: "")
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
                ". Credentials come back only as whether they are set, never as themselves. Also, read only: " +
                OrreryApi.LISTS.sorted().joinToString(", ") + ", what the chat reader may pick from and each reader's last pass.",
            toolSchema("document" to ("string" to "The document name."), required = listOf("document")),
        ),
        write = false,
    ) { args ->
        val name = args.str("document") ?: return@Tool "Error: document is required."
        if (name !in OrreryApi.SETTINGS && name !in OrreryApi.LISTS) return@Tool unknownDoc(name, OrreryApi.SETTINGS + OrreryApi.LISTS)
        orrery.settings(name).fold(
            onSuccess = { clip(it) },
            onFailure = { "Could not read $name: ${it.message}" },
        )
    })

    add(Tool(
        spec = ToolSpec(
            "orrery_configure",
            "Change one of orrery's settings documents: " + OrreryApi.SETTINGS.sorted().joinToString(", ") +
                ". For generator, telegram, chat and mail give only the fields to change: the ship keeps the rest, clears a field given as null, and keeps a stored credential given as \"\". preferences holds the owner's style and standing preferences (\"never propose X\"), which every orrery prompt reads: send style, preferences or both, and since the preferences list replaces the one held, read it first and send it whole with the change. schema and policy are replaced whole, so send the whole document. It answers with the document as stored. Read orrery_guide first: some fields have rules the ship enforces and will refuse.",
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
            onSuccess = { "Sent to $name. The ship now holds: " + clip(it) },
            onFailure = { "Could not write $name: ${it.message}" },
        )
    })

    add(Tool(
        spec = ToolSpec(
            "orrery_preferences",
            "The owner's writing style and standing preferences (\"never propose X\"), which every orrery prompt on the ship reads: the generator, the readers, the refiner and the brief. Read them before changing them.",
            toolSchema(required = emptyList()),
        ),
        write = false,
    ) { _ ->
        orrery.preferences().fold(
            onSuccess = { p ->
                "style: ${p.style.ifBlank { "(none)" }}\n" +
                    if (p.list.isEmpty()) "No standing preferences." else p.list.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")
            },
            onFailure = { "Could not read orrery's preferences: ${it.message}" },
        )
    })

    add(Tool(
        spec = ToolSpec(
            "orrery_set_preferences",
            "Change the owner's standing preferences or writing style in orrery. add puts one preference on the list; remove takes one off, by its text or its number from orrery_preferences; style replaces the style, and \"\" clears it. The list is read from the ship and changed, so a change made elsewhere since stays. The ship keeps up to 30 preferences of up to 300 bytes each and a style of up to 1000 bytes, and refuses more.",
            toolSchema(
                "add" to ("string" to "A preference to add, in the owner's words, e.g. \"Never propose calls before 9am\"."),
                "remove" to ("string" to "A preference to take off: its exact text, or its number from orrery_preferences."),
                "style" to ("string" to "How the owner likes things written; replaces the style. \"\" clears it."),
                required = emptyList(),
            ),
        ),
        write = true,
    ) { args ->
        val add = args.str("add")
        // "" is a style of its own (none), so it is read raw.
        val style = (args["style"] as? JsonPrimitive)?.contentOrNull
        var remove = args.str("remove")
        if (add == null && remove == null && style == null) return@Tool "Error: give add, remove or style."
        remove?.toIntOrNull()?.let { n ->
            val list = orrery.preferences().getOrElse { return@Tool "Could not read orrery's preferences: ${it.message}" }.list
            remove = list.getOrNull(n - 1) ?: return@Tool "Error: there is no preference $n; there are ${list.size}."
        }
        orrery.changePreferences(add, remove, style).fold(
            onSuccess = { p ->
                "Orrery now holds style: ${p.style.ifBlank { "(none)" }}\n" +
                    if (p.list.isEmpty()) "No standing preferences." else p.list.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")
            },
            onFailure = { "Could not change orrery's preferences: ${it.message}" },
        )
    })

    add(Tool(
        spec = ToolSpec(
            "orrery_file_text",
            "Hand orrery text to read and file the way it reads a message: a list of todos for an event with its details, notes from a call, something pasted. The ship's reader files the people, places, plans, dates and tasks in it and may propose actions, such as a calendar entry or a task, for the owner to approve under Actions. It is read soon after, not at once. Prefer this to writing facts one by one when the owner hands you something to keep. Keep the owner's words; give a title saying what it is.",
            toolSchema(
                "text" to ("string" to "The text, as the owner gave it, up to 64 KB."),
                "title" to ("string" to "What it is, e.g. \"Todos for Linus's birthday party\"; optional."),
                required = listOf("text"),
            ),
        ),
        write = true,
    ) { args ->
        val text = args.str("text") ?: return@Tool "Error: text is required."
        orrery.hand(text, args.str("title")).fold(
            onSuccess = { said ->
                val dropped = (said["dropped"] as? JsonPrimitive)?.contentOrNull
                val id = (said["id"] as? JsonPrimitive)?.contentOrNull
                when {
                    dropped != null -> "Orrery did not take it: $dropped. The owner can turn reading on in orrery's settings on the ship (the Read card)."
                    id != null -> "Handed to orrery to read (item $id). It files what it finds soon and may propose actions for the owner to approve under Actions."
                    else -> "Orrery answered, but not with an item: " + clip(said.toString())
                }
            },
            onFailure = { "Could not hand it to orrery: ${it.message}" },
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

private fun unknownDoc(name: String, names: Set<String> = OrreryApi.SETTINGS) =
    "There is no orrery settings document called \"$name\". It is one of: " +
        names.sorted().joinToString(", ") + "."

/** Enough of an answer to work from; the whole state view is long. */
private fun clip(s: String, max: Int = 6000): String =
    if (s.length <= max) s else s.take(max) + "\n… cut here; ask for one body instead."

private fun JsonObject.str(key: String): String? =
    this[key]?.let { (it as? JsonPrimitive)?.contentOrNull }?.takeIf { it.isNotBlank() }

/**
 * What the guide says about the address this install reaches the ship
 * at. Each owner's is their own, so it is read, never written in; and
 * only an HTTPS one off this machine and off a home network can be
 * what Telegram pushes to.
 */
internal fun shipUrlHint(url: String): String {
    val host = url.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
    val private = host == "localhost" || host.endsWith(".local") || host.startsWith("127.") ||
        host.startsWith("10.") || host.startsWith("192.168.") || Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(host)
    val base = if ("://" in url) url.substringBefore("://") + "://" + url.substringAfter("://").substringBefore('/') else url
    return if (url.startsWith("https://") && !private) {
        "This install reaches the owner's ship at $base. Once the owner confirms the internet reaches it there, that is the `public_url` for Telegram, as it is."
    } else {
        "This install reaches the owner's ship at $base, which the internet cannot reach, so it is not a `public_url`. Ask the owner for the ship's public HTTPS address."
    }
}

