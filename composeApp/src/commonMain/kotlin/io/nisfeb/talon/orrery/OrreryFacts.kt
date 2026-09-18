package io.nisfeb.talon.orrery

import io.nisfeb.talon.calendar.CalendarRow
import io.nisfeb.talon.data.ContactEntity
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.mail.InboxEntry
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The structural pipe: facts Talon knows for certain, as orrery bodies
 * and observations. Pure functions over Talon's own rows, so every
 * mapping here is a fixture test away from being checked.
 *
 * Nothing here is a model's claim. Every observation is conf 100, its
 * `at` is the event's own time, and its source is a pointer Talon can
 * open, never the text.
 *
 * Contact is coarsened to the day: `last-contact` is a date, asserted
 * at the start of that day, so a chatty friend costs one observation
 * a day rather than one a message, and a resend is a no-op.
 */
data class OBody(val id: String, val name: String? = null, val aliases: List<String> = emptyList())

data class Obs(
    val subject: String,
    val attr: String,
    val value: JsonElement,
    val atMs: Long,
    val untilMs: Long? = null,
    val conf: Int = 100,
    val sourceKind: String,
    val sourceId: String,
)

data class Facts(val bodies: List<OBody> = emptyList(), val observations: List<Obs> = emptyList()) {
    operator fun plus(o: Facts) = Facts(bodies + o.bodies, observations + o.observations)
}

/** `person/<@p without its sig>`. A comet's `--` is two hyphens, which a slug allows. */
fun personId(ship: String): String = "person/" + ship.removePrefix("~").lowercase()

fun isoUtc(ms: Long): String = Instant.fromEpochMilliseconds(ms).toString()

/** The UTC day [ms] falls in, as its date and the ms at which it starts. */
internal fun dayOf(ms: Long): Pair<String, Long> {
    val date = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.UTC).date
    return date.toString() to date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
}

private fun lastContact(subjectId: String, ms: Long, sourceKind: String, sourceId: String): Obs {
    val (date, start) = dayOf(ms)
    return Obs(subjectId, "last-contact", JsonPrimitive(date), start, sourceKind = sourceKind, sourceId = sourceId)
}

/**
 * A contact as a body: the name they go by, and every handle a triager
 * may meet in text. The id is decided by the pass, which asks the ship
 * first; this only says what the body would look like.
 */
fun personBody(c: ContactEntity, id: String, handle: String?, longHandle: String?): OBody {
    val nick = c.nickname?.trim()?.takeIf { it.isNotEmpty() }
    val aliases = listOfNotNull(nick, c.ship, handle, longHandle).distinct()
    return OBody(id, name = nick ?: handle, aliases = aliases)
}

/**
 * What their status line says, when it says anything and says when.
 *
 * Not a fact, and not sent as one. Tlon's status field is a social
 * field: people write jokes, in-jokes, emoji and quotes in it, and a
 * status in orrery is what somebody is doing or dealing with. So the
 * line goes to the triage like any other text, and what the reader
 * makes of it lands in the tray for the person to judge.
 */
fun contactStatus(c: ContactEntity): Pair<String, Long>? {
    val status = c.status?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val at = c.statusUpdatedMs ?: return null
    return status to at
}

/** What a body says about itself, so a rename is noticed and a replay is not. */
fun bodyDigest(body: OBody): String =
    (listOf(body.name.orEmpty()) + body.aliases.sorted()).joinToString("|").hashCode().toString(16)

/** Somebody wrote to us, or where we could see it. Our own posts say nothing. */
fun messageFacts(m: MessageEntity, ourShip: String, subjectId: String): Obs? {
    if (m.author.isBlank() || m.author == ourShip || !m.author.startsWith("~")) return null
    val kind = if (m.whom.startsWith("~") || m.whom.startsWith("0v")) "talon-dm" else "talon-chat"
    return lastContact(subjectId, m.sentMs, kind, "talon://chat/${m.whom}?id=${m.id}")
}

/** Everyone on a mail thread but us, dated by the thread's last message, capped to now. */
fun mailFacts(e: InboxEntry, ourShip: String, nowMs: Long, idFor: (String) -> String): List<Obs> {
    val at = e.last.coerceAtMost(nowMs)
    if (at <= 0) return emptyList()
    return e.participants.filter { it.startsWith("~") && it != ourShip }.distinct()
        .map { lastContact(idFor(it), at, "mail", "talon://mail/${e.id}") }
}

/**
 * A call whose transcript was just published: a situation with everyone
 * who spoke, and contact with each of them today. The transcript's
 * address is the pointer; its text is for the funnel, later, and never
 * goes up as a value.
 */
fun callFacts(
    address: String,
    title: String,
    speakers: Collection<String>,
    ourShip: String,
    nowMs: Long,
    nameFor: (String) -> String,
): Facts {
    val id = "situation/call-" + (nowMs / 1000)
    val others = speakers.filter { it.startsWith("~") && it != ourShip }.distinct()
    val bodies = listOf(OBody(id, name = title.ifBlank { "Call" })) +
        others.map { OBody(personId(it), aliases = listOf(it, nameFor(it)).distinct()) }
    val obs = buildList {
        fun obs(attr: String, value: JsonElement) = add(Obs(id, attr, value, nowMs, sourceKind = "talon-call", sourceId = address))
        obs("started", JsonPrimitive(isoUtc(nowMs)))
        obs("transcript", JsonPrimitive(address))
        obs("participants", buildJsonObject { put("ref", "person/me") })
        others.forEach { obs("participants", buildJsonObject { put("ref", personId(it)) }) }
        others.forEach { add(lastContact(it, nowMs, "talon-call", address)) }
    }
    return Facts(bodies, obs)
}

// ---- the wire ------------------------------------------------------

const val MAX_BODIES = 50
const val MAX_OBS = 200

fun OBody.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    name?.let { put("name", it) }
    if (aliases.isNotEmpty()) put("aliases", buildJsonArray { aliases.forEach { add(JsonPrimitive(it)) } })
}

fun Obs.toJson(): JsonObject = buildJsonObject {
    put("subject", subject)
    put("attr", attr)
    put("value", value)
    put("at", isoUtc(atMs))
    untilMs?.let { put("until", isoUtc(it)) }
    put("conf", conf)
    put("source", buildJsonObject { put("kind", sourceKind); put("id", sourceId) })
}

/**
 * The batches an observe call takes, within the ship's caps. Bodies
 * go first in batches of their own, so every later observation's
 * subject exists by the time it arrives, whatever batch it lands in.
 */
fun batches(facts: Facts): List<JsonObject> {
    val bodies = facts.bodies.distinctBy { it.id }
    val out = mutableListOf<JsonObject>()
    bodies.chunked(MAX_BODIES).forEach { chunk ->
        out += buildJsonObject {
            put("bodies", buildJsonArray { chunk.forEach { add(it.toJson()) } })
            put("observations", buildJsonArray { })
        }
    }
    facts.observations.chunked(MAX_OBS).forEach { chunk ->
        out += buildJsonObject {
            put("bodies", buildJsonArray { })
            put("observations", buildJsonArray { chunk.forEach { add(it.toJson()) } })
        }
    }
    return out
}
