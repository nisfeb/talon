package io.nisfeb.talon.ui
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.nisfeb.talon.util.secureRandomBytes
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import io.nisfeb.talon.util.ioDispatcher

import io.nisfeb.talon.urbit.TlonChatRepo

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
    /** [message] over the composer; [details], the error whole, behind Copy error details. */
    data class Error(val message: String, val details: String? = null) : CommandResult
}

data class SlashCommandSpec(
    val name: String,
    val synopsis: String,
    val description: String,
)

val SLASH_COMMANDS: List<SlashCommandSpec> = listOf(
    SlashCommandSpec(
        name = "call",
        synopsis = "/call",
        description = "Start a voice call with this chat's peer (desktop, 1:1)",
    ),
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
        name = "invite",
        synopsis = "/invite <group> [~address]",
        description = "Invite someone to one of your groups; in a DM, the other person",
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
        description = "Start a voice recording (${io.nisfeb.talon.ui.tapWord.lowercase()} Stop to send, Cancel to discard)",
    ),
    SlashCommandSpec(
        name = "nick",
        synopsis = "/nick <name>",
        description = "Set your %contacts nickname (visible to peers)",
    ),
    SlashCommandSpec(
        name = "party",
        synopsis = "/party",
        description = "Show who is on this channel's party line (only you see it)",
    ),
    SlashCommandSpec(
        name = "pet",
        synopsis = "/pet ~address <name>",
        description = "Set a local-only pet name for someone (private to you)",
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
        name = "trunk",
        synopsis = "/trunk [people] [2h] [listen] [title]",
        description = "Open a call on your ship and post its link: anyone joins from a browser, no ship needed",
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
    http: io.ktor.client.HttpClient,
    locationProvider: LocationProvider? = null,
    /** Per-device opt-in (UiSettings.powerFeaturesEnabled). When
     *  false, /poke returns an error directing the user to flip the
     *  toggle. Other commands are unaffected. */
    powerFeaturesEnabled: Boolean = false,
    toast: (String) -> Unit = {},
    /** Makes a guest call for /trunk. Null where there are no calls,
     *  and /trunk then says so: it is never posted as text. */
    trunk: TrunkMaker? = null,
    /** Posts a message where the command was typed, for a command whose
     *  result comes after the screen may have gone (/trunk). */
    post: suspend (String) -> Unit = {},
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
        "trunk" -> runTrunk(parsed.args, trunk, post, toast)
        "tz" -> runTz(parsed.args)
        // UI-dispatched commands — DmChatScreen intercepts before this
        // function runs. Recognize them here so unknown-command errors
        // don't fire if someone routes a stale invocation through.
        "img", "file", "mic", "call", "invite" -> CommandResult.Handled
        // Unrecognized slash commands pass through as a normal message
        // (verbatim — original casing/spacing preserved) so server-side
        // bots like hermes can receive their own `/command` syntax. A
        // recognized command with bad usage still returns a helpful
        // Error above; only truly-unknown commands fall through here.
        else -> CommandResult.Send(rawText)
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
        failure("/poke failed", err)
    }
}

/** [err] said in words after [what], never as raw exception text; the error whole goes behind Copy error details. */
private fun failure(what: String, err: Throwable): CommandResult.Error =
    io.nisfeb.talon.util.problemOf(what, err).let { CommandResult.Error(it.line, it.details) }

// ─────────── /cal ───────────

private fun runCal(args: List<String>): CommandResult {
    val raw = args.joinToString(" ").trim()
    return when (val r = parseCalText(raw)) {
        is CalParseResult.Err -> CommandResult.Error("/cal: ${r.error}")
        is CalParseResult.Ok -> {
            val summary = formatCalSummary(r.startMs, r.endMs)
            val tag = encodeCalTag(r.startMs, r.endMs, r.title)
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
            val senderLocal = formatInZone(r.instantMs, r.sourceZoneId)
            val header = "🕒 $senderLocal ${r.sourceLabel}"
            val tag = encodeTzTag(formatIsoUtc(r.instantMs), r.sourceLabel)
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
        // The platforms say why in words ("/loc: no fix available");
        // anything else is said plainly, the error whole behind the button.
        onFailure = { err ->
            val why = io.nisfeb.talon.util.readableReason(err.message?.removePrefix("/loc: "))
            CommandResult.Error("/loc: ${why ?: "couldn't get your location"}", io.nisfeb.talon.util.errorDetailsOf(err))
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
            onFailure = { failure("/nick", it) },
        )
}

// ─────────── /pet ~ship <name> ───────────

private suspend fun runPet(
    args: List<String>,
    repo: TlonChatRepo,
    toast: (String) -> Unit,
): CommandResult {
    if (args.size < 2) {
        return CommandResult.Error("/pet: usage is \"/pet ~address <name>\"")
    }
    val ship = args[0].trim()
    if (!Regex("^~[a-z-]+$").matches(ship)) {
        return CommandResult.Error("/pet: \"$ship\" isn't an address")
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
            onFailure = { failure("/pet", it) },
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
@OptIn(ExperimentalEncodingApi::class)
private fun randomTalkKey(): String {
    val bytes = secureRandomBytes(32)
    return Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes)
}

private fun isValidTalkKey(s: String): Boolean =
    Regex("^[A-Za-z0-9_-]{43}$").matches(s)

// ─────────── /trunk ───────────

/**
 * What /trunk asks for: a link [seats] people may join through, over
 * [ttlSecs] seconds. Everyone who opens the link takes a place, the
 * host too: a line of its own has no way in from Talon yet.
 */
data class TrunkAsk(val seats: Int = 10, val ttlSecs: Int = 86_400, val speak: Boolean = true, val title: String = "")

sealed interface TrunkParse {
    data class Ok(val ask: TrunkAsk) : TrunkParse
    data class Problem(val message: String) : TrunkParse
}

/**
 * Makes the call and hands its link to [post]. It throws
 * [io.nisfeb.talon.call.GuestCallRefused] with the reason when no call
 * was made.
 */
typealias TrunkMaker = suspend (ask: TrunkAsk, post: suspend (link: String) -> Unit) -> Unit

private val TRUNK_SPAN = Regex("^(\\d+)([mhd])$")

/**
 * `/trunk [people] [2h] [listen] [title]`: a number is how many people
 * the link takes, a number with m, h or d is how long it works, and
 * `listen` makes guests listeners. They come in any order, and the
 * title is everything from the first word that is none of them. The
 * limits are trunk's own: 1,000 guests and 30 days.
 */
fun parseTrunk(args: List<String>): TrunkParse {
    var ask = TrunkAsk()
    var i = 0
    var seats = false; var span = false; var listen = false
    while (i < args.size) {
        val a = args[i].lowercase()
        val m = TRUNK_SPAN.find(a)
        when {
            !seats && a.all { it.isDigit() } && a.isNotEmpty() -> {
                val n = a.toIntOrNull()
                if (n == null || n !in 1..1000) return TrunkParse.Problem("/trunk: a link takes 1 to 1000 people")
                ask = ask.copy(seats = n); seats = true
            }
            !span && m != null -> {
                val unit = when (m.groupValues[2]) { "m" -> 60L; "h" -> 3_600L; else -> 86_400L }
                val secs = (m.groupValues[1].toLongOrNull() ?: Long.MAX_VALUE / 86_400L) * unit
                if (secs !in 60L..30L * 86_400L) return TrunkParse.Problem("/trunk: a link works for 1 minute to 30 days, like 45m, 2h or 7d")
                ask = ask.copy(ttlSecs = secs.toInt()); span = true
            }
            !listen && a == "listen" -> { ask = ask.copy(speak = false); listen = true }
            else -> break
        }
        i++
    }
    return TrunkParse.Ok(ask.copy(title = args.drop(i).joinToString(" ").take(64)))
}

private fun spanWords(secs: Int): String {
    fun n(count: Int, word: String) = "$count $word" + if (count == 1) "" else "s"
    return when {
        secs % 86_400 == 0 -> n(secs / 86_400, "day")
        secs % 3_600 == 0 -> n(secs / 3_600, "hour")
        else -> n(secs / 60, "minute")
    }
}

/** The message /trunk posts. */
fun trunkMessage(ask: TrunkAsk, link: String): String =
    "📞 ${ask.title.ifEmpty { "Call" }}: $link\n" +
        "Open it in any browser to join. No account is needed. " +
        (if (ask.speak) "" else "Guests listen and watch. ") +
        "The link takes ${ask.seats} ${if (ask.seats == 1) "person" else "people"} and works for ${spanWords(ask.ttlSecs)}."

/** The link: the ship's public address where the owner set one, else the address we signed in at. */
fun guestLink(origin: String, loginUrl: String, path: String): String =
    origin.ifEmpty { loginUrl }.trimEnd('/') + path

/** Whether [url] is an address only this machine or its own network reaches. */
fun isPrivateAddress(url: String): Boolean {
    val host = url.substringAfter("://").substringBefore('/').substringBeforeLast(':').lowercase()
    val o = host.split('.').mapNotNull { it.toIntOrNull() }
    return host == "localhost" || host.endsWith(".local") || host.endsWith(".lan") || '.' !in host ||
        (o.size == 4 && (o[0] == 127 || o[0] == 10 || (o[0] == 192 && o[1] == 168) || (o[0] == 172 && o[1] in 16..31)))
}

/** The [TrunkMaker] both shells pass: [controller] makes the call, and the link is built on [loginUrl]. */
fun trunkMaker(controller: io.nisfeb.talon.call.CallController, loginUrl: () -> String?): TrunkMaker = { ask, post ->
    val base = loginUrl() ?: throw io.nisfeb.talon.call.GuestCallRefused("Not signed in to a ship.")
    controller.guestCall(ask.title.ifEmpty { "Call" }, ask.seats, ask.ttlSecs, ask.speak) { call ->
        post(guestLink(call.origin, base, call.path))
    }
}

private suspend fun runTrunk(
    args: List<String>,
    trunk: TrunkMaker?,
    post: suspend (String) -> Unit,
    toast: (String) -> Unit,
): CommandResult {
    if (trunk == null) return CommandResult.Error("/trunk: calls can't be made from here")
    val ask = when (val p = parseTrunk(args)) {
        is TrunkParse.Problem -> return CommandResult.Error(p.message)
        is TrunkParse.Ok -> p.ask
    }
    return try {
        trunk(ask) { link ->
            post(trunkMessage(ask, link))
            // Said after the post: the link is still good for those it reaches.
            when {
                isPrivateAddress(link) ->
                    toast("That link is on an address only your own network reaches. Set your ship's public address on its trunk page.")
                link.startsWith("http://") ->
                    toast("That link is not https, and browsers give a microphone only over https. Set your ship's https address on its trunk page.")
            }
        }
        CommandResult.Handled
    } catch (e: io.nisfeb.talon.call.GuestCallRefused) {
        CommandResult.Error("/trunk: ${e.message}")
    }
}

// ─────────── /hn ───────────

private suspend fun runHn(http: io.ktor.client.HttpClient): CommandResult {
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
    }.getOrElse {
        // Not the ship: Hacker News, which problemOf would call the ship.
        CommandResult.Error("/hn: couldn't reach Hacker News", io.nisfeb.talon.util.errorDetailsOf(it))
    }
}

private suspend fun fetchJsonArray(
    http: io.ktor.client.HttpClient,
    url: String,
): kotlinx.serialization.json.JsonArray? = kotlinx.coroutines.withContext(ioDispatcher) {
    val resp = http.get(url)
    if (!resp.status.isSuccess()) return@withContext null
    val body = resp.bodyAsText()
    val el = kotlinx.serialization.json.Json.parseToJsonElement(body)
    el as? kotlinx.serialization.json.JsonArray
}

private suspend fun fetchJsonObject(
    http: io.ktor.client.HttpClient,
    url: String,
): Map<String, Any?>? = kotlinx.coroutines.withContext(ioDispatcher) {
    val resp = http.get(url)
    if (!resp.status.isSuccess()) return@withContext null
    val body = resp.bodyAsText()
    val obj = kotlinx.serialization.json.Json.parseToJsonElement(body)
        as? kotlinx.serialization.json.JsonObject ?: return@withContext null
    obj.mapValues { (_, v) ->
        val p = v as? kotlinx.serialization.json.JsonPrimitive
        if (p == null) null
        else if (p.isString) p.content
        else p.content.toLongOrNull() ?: p.content
    }
}
