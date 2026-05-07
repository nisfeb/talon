package io.nisfeb.talon.ui

import io.nisfeb.talon.urbit.TlonChatRepo
import java.security.SecureRandom
import java.util.Base64

/**
 * Outcome of running a slash command. UI side decides what to do:
 * `Send` body goes through the normal send path; `Handled` means the
 * command already did its work (e.g. poked an agent); `NotACommand`
 * means the input wasn't a slash command at all; `Error` bubbles a
 * user-facing message to the composer.
 *
 * Mirrors yap/ui/src/util/commands.ts — see that file for the source
 * commands list + rationale.
 */
sealed interface CommandResult {
    data class Send(val body: String) : CommandResult
    object Handled : CommandResult
    object NotACommand : CommandResult
    data class Error(val message: String) : CommandResult
}

data class SlashCommandSpec(
    val name: String,
    val synopsis: String,
    val description: String,
)

val SLASH_COMMANDS: List<SlashCommandSpec> = listOf(
    SlashCommandSpec(
        name = "cal",
        synopsis = "/cal <when> [title]",
        description = "Attach a calendar event, e.g. \"/cal thurs 2-3p Meet John\"",
    ),
    SlashCommandSpec(
        name = "file",
        synopsis = "/file",
        description = "Pick a file to attach (same as the paperclip button)",
    ),
    SlashCommandSpec(
        name = "hn",
        synopsis = "/hn",
        description = "Drop the current top Hacker News story into the chat",
    ),
    SlashCommandSpec(
        name = "img",
        synopsis = "/img",
        description = "Pick an image to send (same as the image button)",
    ),
    SlashCommandSpec(
        name = "loc",
        synopsis = "/loc",
        description = "Share your current location — a one-shot OSM link",
    ),
    SlashCommandSpec(
        name = "me",
        synopsis = "/me <action>",
        description = "IRC-style action line, rendered in italics",
    ),
    SlashCommandSpec(
        name = "mic",
        synopsis = "/mic",
        description = "Start a voice recording (tap Stop to send, Cancel to discard)",
    ),
    SlashCommandSpec(
        name = "nick",
        synopsis = "/nick <name>",
        description = "Set your %contacts nickname (visible to peers)",
    ),
    SlashCommandSpec(
        name = "pet",
        synopsis = "/pet ~ship <name>",
        description = "Set a local-only pet name for a ship (private to you)",
    ),
    SlashCommandSpec(
        name = "poll",
        synopsis = "/poll q? | a | b | …",
        description = "Quick inline poll; react with 1️⃣ 2️⃣ … to vote",
    ),
    SlashCommandSpec(
        name = "talk",
        synopsis = "/talk",
        description = "Drop a fresh Brave Talk voice/video room into the chat",
    ),
    SlashCommandSpec(
        name = "tz",
        synopsis = "/tz <time> [zone]",
        description = "Share a time across zones — everyone sees it localized",
    ),
    SlashCommandSpec(
        name = "poke",
        synopsis = "/poke <app> <mark> <json>",
        description = "Power user: poke an agent on your ship with raw JSON. " +
            "Off by default — enable in Settings → Power features.",
    ),
)

/**
 * Autocomplete filter. Prefix hits first, substring hits after.
 * Case-insensitive. Empty query returns the full catalog in its
 * declared order.
 */
fun filterSlashCommands(query: String): List<SlashCommandSpec> {
    val q = query.lowercase()
    if (q.isEmpty()) return SLASH_COMMANDS
    val pref = mutableListOf<SlashCommandSpec>()
    val sub = mutableListOf<SlashCommandSpec>()
    for (s in SLASH_COMMANDS) {
        when {
            s.name.startsWith(q) -> pref += s
            s.name.contains(q) -> sub += s
        }
    }
    return pref + sub
}

/**
 * Dispatch a raw composer string. Returns [CommandResult.NotACommand]
 * if the text doesn't start with `/`, so callers can chain this in
 * front of the normal send path.
 */
/**
 * `locationProvider`: optional suspend lambda for /loc. Returns
 * `Result.success(lat to lng)` when a fix is available, `Result.failure`
 * when permission is missing or no fix yet, or null entirely when the
 * platform doesn't support sensors. Android passes a Context-backed
 * provider; desktop passes null.
 */
typealias LocationProvider = suspend () -> Result<Pair<Double, Double>>

suspend fun runCommand(
    rawText: String,
    repo: TlonChatRepo,
    http: okhttp3.OkHttpClient,
    locationProvider: LocationProvider? = null,
    /** Per-device opt-in (UiSettings.powerFeaturesEnabled). When
     *  false, /poke returns an error directing the user to flip the
     *  toggle. Other commands are unaffected. */
    powerFeaturesEnabled: Boolean = false,
    toast: (String) -> Unit = {},
): CommandResult {
    val parsed = parseSlash(rawText) ?: return CommandResult.NotACommand
    return when (parsed.cmd) {
        "cal" -> runCal(parsed.args)
        "hn" -> runHn(http)
        "loc" -> runLoc(parsed.args, locationProvider)
        "me" -> runMe(parsed.args)
        "nick" -> runNick(parsed.args, repo, toast)
        "pet" -> runPet(parsed.args, repo, toast)
        "poll" -> runPoll(parsed.args)
        "poke" -> runPoke(parsed.args, repo, powerFeaturesEnabled, toast)
        "talk" -> runTalk(parsed.args)
        "tz" -> runTz(parsed.args)
        // UI-dispatched commands — DmChatScreen intercepts before this
        // function runs. Recognize them here so unknown-command errors
        // don't fire if someone routes a stale invocation through.
        "img", "file", "mic" -> CommandResult.Handled
        else -> CommandResult.Error("unknown command: /${parsed.cmd}")
    }
}

// ─────────── /poke ───────────

private suspend fun runPoke(
    args: List<String>,
    repo: TlonChatRepo,
    powerFeaturesEnabled: Boolean,
    toast: (String) -> Unit,
): CommandResult {
    if (!powerFeaturesEnabled) {
        return CommandResult.Error(
            "/poke is off — enable Settings → Power features to use this",
        )
    }
    if (args.size < 3) {
        return CommandResult.Error("/poke usage: /poke <app> <mark> <json>")
    }
    val app = args[0]
    val mark = args[1]
    val payloadRaw = args.drop(2).joinToString(" ").trim()
    val payload = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(payloadRaw)
    }.getOrElse {
        return CommandResult.Error(
            "/poke: payload must be valid JSON (got: ${it.message ?: "parse failed"})",
        )
    }
    return runCatching {
        repo.pokeRaw(app, mark, payload)
        toast("/poke %$app · $mark sent")
        CommandResult.Handled as CommandResult
    }.getOrElse { err ->
        CommandResult.Error("/poke failed: ${err.message ?: err::class.simpleName}")
    }
}

// ─────────── /cal ───────────

private fun runCal(args: List<String>): CommandResult {
    val raw = args.joinToString(" ").trim()
    return when (val r = parseCalText(raw)) {
        is CalParseResult.Err -> CommandResult.Error("/cal: ${r.error}")
        is CalParseResult.Ok -> {
            val summary = formatCalSummary(r.start, r.end)
            val tag = encodeCalTag(r.start, r.end, r.title)
            CommandResult.Send("📅 ${r.title}\n$summary\n$tag")
        }
    }
}

// ─────────── /tz ───────────

private fun runTz(args: List<String>): CommandResult {
    val raw = args.joinToString(" ").trim()
    return when (val r = parseTzInput(raw)) {
        is TzParseResult.Err -> CommandResult.Error("/tz: ${r.error}")
        is TzParseResult.Ok -> {
            val senderLocal = formatInZone(r.instant, r.sourceZone)
            val header = "🕒 $senderLocal ${r.sourceLabel}"
            val tag = encodeTzTag(formatIsoUtc(r.instant), r.sourceLabel)
            CommandResult.Send("$header\n$tag")
        }
    }
}

// ─────────── /poll ───────────

private fun runPoll(args: List<String>): CommandResult {
    val raw = args.joinToString(" ").trim()
    return when (val r = parsePollInput(raw)) {
        is PollParseResult.Err -> CommandResult.Error("/poll: ${r.error}")
        is PollParseResult.Ok -> {
            val lines = r.poll.options.mapIndexed { i, o -> "${VOTE_EMOJIS[i]} $o" }
            val body = "📊 ${r.poll.question}\n${lines.joinToString("\n")}\n${encodePollTag(r.poll)}"
            CommandResult.Send(body)
        }
    }
}

// ─────────── /loc ───────────

private suspend fun runLoc(
    @Suppress("UNUSED_PARAMETER") args: List<String>,
    locationProvider: LocationProvider?,
): CommandResult {
    if (locationProvider == null) {
        return CommandResult.Error(
            "/loc: location sensors aren't available on this platform yet"
        )
    }
    val result = locationProvider()
    return result.fold(
        onSuccess = { (lat, lng) -> CommandResult.Send(formatLocationShare(lat, lng)) },
        onFailure = { err ->
            CommandResult.Error(
                err.message ?: "/loc: location fetch failed"
            )
        },
    )
}

// ─────────── /me <action> ───────────

private fun runMe(args: List<String>): CommandResult {
    val action = args.joinToString(" ").trim()
    if (action.isEmpty()) {
        return CommandResult.Error("/me: say what you did, e.g. \"/me shrugs\"")
    }
    // The markdown renderer handles *italic*; the author row already
    // shows who "me" is, so no extra tag.
    return CommandResult.Send("*$action*")
}

// ─────────── /nick <name> ───────────

private suspend fun runNick(
    args: List<String>,
    repo: TlonChatRepo,
    toast: (String) -> Unit,
): CommandResult {
    val name = args.joinToString(" ").trim()
    if (name.isEmpty()) return CommandResult.Error("/nick: give a name")
    if (name.length > 64) return CommandResult.Error("/nick: name too long (max 64)")
    return runCatching { repo.updateProfile(nickname = name) }
        .fold(
            onSuccess = {
                toast("nickname set to \"$name\"")
                CommandResult.Handled
            },
            onFailure = { CommandResult.Error("/nick: ${it.message ?: it::class.simpleName}") },
        )
}

// ─────────── /pet ~ship <name> ───────────

private suspend fun runPet(
    args: List<String>,
    repo: TlonChatRepo,
    toast: (String) -> Unit,
): CommandResult {
    if (args.size < 2) {
        return CommandResult.Error("/pet: usage is \"/pet ~ship <name>\"")
    }
    val ship = args[0].trim()
    if (!Regex("^~[a-z-]+$").matches(ship)) {
        return CommandResult.Error("/pet: \"$ship\" isn't a ship patp")
    }
    val name = args.drop(1).joinToString(" ").trim()
    if (name.isEmpty()) return CommandResult.Error("/pet: give a pet name")
    if (name.length > 64) return CommandResult.Error("/pet: name too long (max 64)")
    return runCatching { repo.setPetName(ship, name) }
        .fold(
            onSuccess = {
                toast("pet name for $ship: \"$name\"")
                CommandResult.Handled
            },
            onFailure = { CommandResult.Error("/pet: ${it.message ?: it::class.simpleName}") },
        )
}

// ─────────── /talk [key] ───────────

private fun runTalk(args: List<String>): CommandResult {
    val slug = args.firstOrNull()?.trim().orEmpty()
    if (slug.isNotEmpty() && !isValidTalkKey(slug)) {
        return CommandResult.Error(
            "/talk: optional arg must be a Brave Talk key from an existing link (or omit for a new room)",
        )
    }
    val room = slug.ifEmpty { randomTalkKey() }
    return CommandResult.Send("🎙️ Brave Talk: https://talk.brave.com/$room")
}

/** 32 random bytes, base64url-encoded (43 chars, no padding) — same
 *  format Brave Talk uses for its E2EE room keys. */
private fun randomTalkKey(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private fun isValidTalkKey(s: String): Boolean =
    Regex("^[A-Za-z0-9_-]{43}$").matches(s)

// ─────────── /hn ───────────

private suspend fun runHn(http: okhttp3.OkHttpClient): CommandResult {
    return runCatching {
        val ids = fetchJsonArray(http, "https://hacker-news.firebaseio.com/v0/topstories.json")
            ?: return CommandResult.Error("/hn: empty top-stories list")
        if (ids.isEmpty()) return CommandResult.Error("/hn: empty top-stories list")
        val id = ids.first().toString().toLongOrNull()
            ?: return CommandResult.Error("/hn: unexpected top-stories shape")
        val story = fetchJsonObject(http, "https://hacker-news.firebaseio.com/v0/item/$id.json")
            ?: return CommandResult.Error("/hn: story fetch failed")
        val title = story["title"] ?: "(untitled)"
        val url = story["url"] ?: "https://news.ycombinator.com/item?id=$id"
        val scorePart = story["score"]?.let { " · $it pts" }.orEmpty()
        val comments = "https://news.ycombinator.com/item?id=$id"
        CommandResult.Send("📰 $title$scorePart\n$url\n💬 $comments")
    }.getOrElse { CommandResult.Error("/hn: ${it.message ?: it::class.simpleName}") }
}

private suspend fun fetchJsonArray(
    http: okhttp3.OkHttpClient,
    url: String,
): kotlinx.serialization.json.JsonArray? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val req = okhttp3.Request.Builder().url(url).get().build()
    http.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) return@withContext null
        val body = resp.body?.string() ?: return@withContext null
        val el = kotlinx.serialization.json.Json.parseToJsonElement(body)
        el as? kotlinx.serialization.json.JsonArray
    }
}

private suspend fun fetchJsonObject(
    http: okhttp3.OkHttpClient,
    url: String,
): Map<String, Any?>? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val req = okhttp3.Request.Builder().url(url).get().build()
    http.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) return@withContext null
        val body = resp.body?.string() ?: return@withContext null
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(body)
            as? kotlinx.serialization.json.JsonObject ?: return@withContext null
        obj.mapValues { (_, v) ->
            val p = v as? kotlinx.serialization.json.JsonPrimitive
            if (p == null) null
            else if (p.isString) p.content
            else p.content.toLongOrNull() ?: p.content
        }
    }
}
