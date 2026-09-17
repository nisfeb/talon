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

private fun lastContact(ship: String, ms: Long, sourceKind: String, sourceId: String): Obs {
    val (date, start) = dayOf(ms)
    return Obs(personId(ship), "last-contact", JsonPrimitive(date), start, sourceKind = sourceKind, sourceId = sourceId)
}

/**
 * A contact as a body: the name they go by, every handle a triager may
 * meet in text, and their status line when it carries a time. Our own
 * contact row is `person/me`, which the ship already made.
 */
fun contactFacts(c: ContactEntity, ourShip: String, handle: String?, longHandle: String?): Facts {
    val id = if (c.ship == ourShip) "person/me" else personId(c.ship)
    val nick = c.nickname?.trim()?.takeIf { it.isNotEmpty() }
    val aliases = listOfNotNull(nick, c.ship, handle, longHandle).distinct()
    val body = OBody(id, name = nick ?: handle, aliases = aliases)
    val status = c.status?.trim()?.takeIf { it.isNotEmpty() }
    val at = c.statusUpdatedMs
    val obs = if (status != null && at != null) {
        listOf(Obs(id, "status", JsonPrimitive(status), at, sourceKind = "contacts", sourceId = c.ship))
    } else emptyList()
    return Facts(listOf(body), obs)
}

/** Somebody wrote to us, or where we could see it. Our own posts say nothing. */
fun messageFacts(m: MessageEntity, ourShip: String): Obs? {
    if (m.author.isBlank() || m.author == ourShip || !m.author.startsWith("~")) return null
    val kind = if (m.whom.startsWith("~") || m.whom.startsWith("0v")) "talon-dm" else "talon-chat"
    return lastContact(m.author, m.sentMs, kind, "talon://chat/${m.whom}?id=${m.id}")
}

/** Everyone on a mail thread but us, dated by the thread's last message, capped to now. */
fun mailFacts(e: InboxEntry, ourShip: String, nowMs: Long): List<Obs> {
    val at = e.last.coerceAtMost(nowMs)
    if (at <= 0) return emptyList()
    return e.participants.filter { it.startsWith("~") && it != ourShip }.distinct()
        .map { lastContact(it, at, "mail", "talon://mail/${e.id}") }
}

/**
 * One occurrence as a situation that is under way between its ends, and
 * where it puts us. A recurring event becomes an activity instead;
 * [calendarFacts] is what routes them.
 */
fun eventFacts(row: CalendarRow, ourShip: String): Facts {
    if (row.isTask || row.name.isBlank() || row.r <= row.l) return Facts()
    val id = situationId(row)
    val source = "${row.cal}/${row.id}" + if (row.idx > 0) "/${row.idx}" else ""
    val body = OBody(id, name = row.name, aliases = row.tags.filter { it.isNotBlank() })
    val obs = buildList {
        fun obs(attr: String, value: JsonElement, untilMs: Long? = null, conf: Int = 100) =
            add(Obs(id, attr, value, row.l, untilMs, conf, "calendar", source))
        obs("status", JsonPrimitive("under way"), untilMs = row.r)
        obs("started", JsonPrimitive(isoUtc(row.l)))
        obs("ended", JsonPrimitive(isoUtc(row.r)))
        obs("participants", buildJsonObject { put("ref", "person/me") })
        if (row.location.isNotBlank()) {
            obs("location", JsonPrimitive(row.location))
            add(Obs("person/me", "location", JsonPrimitive(row.location), row.l, row.r, 60, "calendar", source))
        }
    }
    return Facts(listOf(body), obs)
}

/**
 * A window of the calendar: what happens once is a situation, and what
 * recurs is one activity carrying its cadence and its last and next,
 * not a body per occurrence. A standing weekly meeting is one thing in
 * the world that keeps happening, which is what the kind is for.
 */
fun calendarFacts(rows: List<CalendarRow>, ourShip: String, nowMs: Long): Facts {
    val usable = rows.filter { !it.isTask && it.name.isNotBlank() && it.r > it.l }
    var out = Facts()
    usable.filterNot { it.repeats }.forEach { out += eventFacts(it, ourShip) }
    usable.filter { it.repeats }
        .groupBy { it.cal to it.id }
        .forEach { (_, occurrences) -> out += activityFacts(occurrences, ourShip, nowMs) }
    return out
}

/**
 * One recurring event, from the occurrences of it the window holds.
 *
 * Every `at` here comes from an occurrence rather than from the clock,
 * because an observation's id hashes its `at`: asserting "next is
 * Tuesday" at the moment of each pass would write a new row every ten
 * minutes. Anchored this way, a pass that learns nothing new writes
 * nothing new, and the rows turn over once per occurrence.
 *
 * ponytail: the series' own attributes are re-asserted whenever the
 * last occurrence moves, since the ship is not read back before
 * writing. Reading the body first would cut that to one row per real
 * change; retention culls the superseded ones meanwhile.
 */
fun activityFacts(occurrences: List<CalendarRow>, ourShip: String, nowMs: Long): Facts {
    val rows = occurrences.filter { !it.isTask && it.name.isNotBlank() && it.r > it.l }.sortedBy { it.l }
    val first = rows.firstOrNull() ?: return Facts()
    val id = activityId(first)
    val source = "${first.cal}/${first.id}"
    val body = OBody(id, name = first.name, aliases = first.tags.filter { it.isNotBlank() })
    val last = rows.lastOrNull { it.l <= nowMs }
    val next = rows.firstOrNull { it.l > nowMs }
    val underWay = rows.firstOrNull { it.l <= nowMs && nowMs < it.r }
    // What the series is, as of the occurrence it was last read from.
    val asOf = last?.l ?: first.l
    val obs = buildList {
        fun obs(attr: String, value: JsonElement, at: Long, untilMs: Long? = null, conf: Int = 100) =
            add(Obs(id, attr, value, at, untilMs, conf, "calendar", source))
        obs("cadence", JsonPrimitive(first.kind), asOf)
        obs("participants", buildJsonObject { put("ref", "person/me") }, asOf)
        if (first.location.isNotBlank()) obs("location", JsonPrimitive(first.location), asOf)
        last?.let { obs("last", JsonPrimitive(isoUtc(it.l)), it.l) }
        // The next one became the next when the previous ended. With no
        // previous in the window, the day is the steadiest anchor there is.
        next?.let { obs("next", JsonPrimitive(isoUtc(it.l)), last?.r ?: dayOf(nowMs).second) }
        underWay?.let {
            obs("status", JsonPrimitive("under way"), it.l, untilMs = it.r)
            if (first.location.isNotBlank()) {
                add(Obs("person/me", "location", JsonPrimitive(first.location), it.l, it.r, 60, "calendar", source))
            }
        }
    }
    return Facts(listOf(body), obs)
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

/** `situation/cal-<calendar>-<event>[-<instance>]`, within the slug's 64 bytes. */
internal fun situationId(row: CalendarRow): String = calId("situation", row, withInstance = true)

/** `activity/cal-<calendar>-<event>`: the series, not one of its occurrences. */
internal fun activityId(row: CalendarRow): String = calId("activity", row, withInstance = false)

private fun calId(kind: String, row: CalendarRow, withInstance: Boolean): String {
    val raw = "cal-${row.cal}-${row.id}" + if (withInstance && row.idx > 0) "-${row.idx}" else ""
    val slug = raw.lowercase().replace(Regex("[^a-z0-9-]+"), "-").trim('-').replace(Regex("-{3,}"), "--")
    return "$kind/" + slug.take(64).trimEnd('-')
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
