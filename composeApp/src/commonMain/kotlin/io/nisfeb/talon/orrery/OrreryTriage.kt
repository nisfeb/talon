package io.nisfeb.talon.orrery

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The funnel, without the model: which messages are worth a look, who
 * they name, and the few shapes of claim that need no model to read.
 *
 * Pure, so every rule is a fixture away from being checked. The rules
 * are the floor the model ladder stands on: a status line, where
 * somebody says they are, where they say a known body is. They claim
 * little, at conf 60 to 70, and everything they claim goes through the
 * Noticed tray until the person has trusted that kind of claim.
 */
data class KnownBody(val id: String, val name: String?, val aliases: List<String>, val ship: String?)

/** One claim the triage noticed, with the body it would need. */
data class Noticed(
    val subject: String,
    val attr: String,
    val value: JsonElement,
    val atMs: Long,
    val untilMs: Long?,
    val conf: Int,
    val body: OBody? = null,
)

/**
 * Names to bodies, from the state view. A match is whole-word and
 * case-insensitive, over each body's name, aliases and ship, so "Sam"
 * does not find "Samantha" and a ship's @p finds its person.
 */
class NameIndex(bodies: List<KnownBody>) {
    private val entries: List<Pair<Regex, KnownBody>> = bodies.flatMap { b ->
        (b.aliases + listOfNotNull(b.name, b.ship))
            .map { it.trim() }
            .filter { it.length >= 2 && !it.equals("me", ignoreCase = true) && !it.equals("I", ignoreCase = true) }
            .distinct()
            .map { Regex("(?<![\\w~])" + Regex.escape(it) + "(?![\\w-])", RegexOption.IGNORE_CASE) to b }
    }
    private val byShip: Map<String, KnownBody> = bodies.filter { it.ship != null }.associateBy { it.ship!! }
    private val byId: Map<String, KnownBody> = bodies.associateBy { it.id }

    fun has(id: String): Boolean = id in byId

    fun forShip(ship: String): KnownBody? = byShip[ship]

    /** Bodies named in [text], each once, with the alias that named them. */
    fun find(text: String): List<Pair<KnownBody, String>> {
        val seen = HashSet<String>()
        return entries.mapNotNull { (re, b) ->
            val m = re.find(text) ?: return@mapNotNull null
            if (!seen.add(b.id)) return@mapNotNull null
            b to m.value
        }
    }

    /** The body a place name refers to, when one is known by that name. */
    fun place(name: String): KnownBody? =
        find(name).firstOrNull { (b, alias) -> b.id.startsWith("place/") && alias.equals(name.trim(), ignoreCase = true) }?.first
}

/**
 * Whether a message is the triage's business at all. A DM or a group
 * DM always is. A channel post is when the channel is allowed, or when
 * it names us.
 */
fun inScope(whom: String, text: String, ourShip: String, ourNick: String?, allowedChannels: Set<String>): Boolean {
    if (whom.startsWith("~") || whom.startsWith("0v")) return true
    if (whom in allowedChannels) return true
    if (text.contains(ourShip, ignoreCase = true)) return true
    val nick = ourNick?.trim().orEmpty()
    return nick.length >= 2 && Regex("(?<!\\w)" + Regex.escape(nick) + "(?!\\w)", RegexOption.IGNORE_CASE).containsMatchIn(text)
}

private const val SIX_HOURS = 6L * 60 * 60 * 1000

private val I_AM = "(?:i'?m|i am|we'?re|we are)"
private val PLACE = "([^.,!?;\\n]{2,40}?)(?=[.,!?;\\n]|\\s+(?:now|today|tonight|right now|atm|for|until|till)\\b|$)"
private val RE_I_AT = Regex("\\b$I_AM\\s+(?:at|in)\\s+(?:the\\s+)?$PLACE", RegexOption.IGNORE_CASE)
private val RE_I_HOME = Regex("\\b$I_AM\\s+(?:back\\s+)?(?:at\\s+)?home\\b", RegexOption.IGNORE_CASE)
private val RE_I_STATUS = Regex("\\b(?:i'?m|i am)\\s+(stranded|stuck|sick|ill|busy|free|on my way|running late|late|off today|out of office|on vacation|travelling|traveling)\\b", RegexOption.IGNORE_CASE)
private val RE_X_AT = Regex("\\b(%s)\\s+(?:is|'s|was)\\s+(?:at|in)\\s+(?:the\\s+)?$PLACE", RegexOption.IGNORE_CASE)
private val RE_X_STATUS = Regex("\\b(%s)\\s+(?:is|'s)\\s+(sick|ill|stranded|stuck|fine|ok|okay|back|home|away|busy|free)\\b", RegexOption.IGNORE_CASE)

/**
 * What [text] claims, by the rules. [author] is who said it: "I" is
 * them, and a body named in the text is itself. Nothing here reads a
 * negation, a question or a quote, so a claim in a question is a
 * claim the tray will show and the person will discard.
 */
fun ruleFacts(text: String, author: String, atMs: Long, ourShip: String, index: NameIndex): List<Noticed> {
    if (text.isBlank() || text.trimEnd().endsWith("?")) return emptyList()
    val out = mutableListOf<Noticed>()
    val self = if (author == ourShip) "person/me" else personId(author)
    val selfBody = if (author == ourShip || index.has(self)) null else OBody(self, aliases = listOf(author))
    fun place(raw: String): JsonElement {
        val name = raw.trim().trimEnd('.', ',', '!')
        // The pattern eats a leading "the", and a place's alias may keep it.
        val known = index.place(name) ?: index.place("the $name")
        return known?.let { buildJsonObject { put("ref", it.id) } } ?: JsonPrimitive(name)
    }

    RE_I_AT.find(text)?.let { m ->
        out += Noticed(self, "location", place(m.groupValues[1]), atMs, atMs + SIX_HOURS, 70, selfBody)
    }
    if (out.none { it.attr == "location" }) {
        RE_I_HOME.find(text)?.let {
            val home = index.place("home")?.let { b -> buildJsonObject { put("ref", b.id) } } ?: JsonPrimitive("home")
            out += Noticed(self, "location", home, atMs, atMs + SIX_HOURS, 70, selfBody)
        }
    }
    RE_I_STATUS.find(text)?.let { m ->
        out += Noticed(self, "status", JsonPrimitive(m.groupValues[1].lowercase()), atMs, atMs + SIX_HOURS, 70, selfBody)
    }

    val named = index.find(text)
    if (named.isNotEmpty()) {
        val alt = named.joinToString("|") { (_, alias) -> Regex.escape(alias) }
        Regex(RE_X_AT.pattern.replace("%s", alt), RegexOption.IGNORE_CASE).find(text)?.let { m ->
            val body = named.first { (_, alias) -> alias.equals(m.groupValues[1], ignoreCase = true) }.first
            if (body.id != self) out += Noticed(body.id, "location", place(m.groupValues[2]), atMs, atMs + SIX_HOURS, 60)
        }
        Regex(RE_X_STATUS.pattern.replace("%s", alt), RegexOption.IGNORE_CASE).find(text)?.let { m ->
            val body = named.first { (_, alias) -> alias.equals(m.groupValues[1], ignoreCase = true) }.first
            if (body.id != self) out += Noticed(body.id, "status", JsonPrimitive(m.groupValues[2].lowercase()), atMs, atMs + SIX_HOURS, 60)
        }
    }
    return out.distinctBy { it.subject to it.attr }
}

/** Stable per claim and source, so a message re-read on the next pass is the same row. */
fun noticedId(sourceId: String, subject: String, attr: String): String = "$sourceId|$subject|$attr"
