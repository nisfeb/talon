package io.nisfeb.talon.orrery

import io.nisfeb.talon.calendar.CalendarRow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Deciding what a calendar event is to the ship, by the rules in
 * orrery-utils' `docs/writing-a-client.md`: resolve before creating, a
 * hit is the thing itself, an occurrence lands on its activity, and a
 * body is made once or not at all.
 *
 * Pure. The pass does the asking and the remembering; everything here
 * is a decision about what to write, which is the part worth pinning
 * with fixtures.
 */

/** One event and the occurrences of it the window holds, newest last. */
data class CalendarSubject(
    val cal: String,
    val uid: String,
    val title: String,
    val repeats: Boolean,
    val occurrences: List<CalendarRow>,
) {
    val first: CalendarRow get() = occurrences.first()
    /** The key this install remembers its decision under. */
    val key: String get() = "cal:$cal/$uid"
    val location: String get() = first.location
}

/** What to write for one subject, and what to remember afterwards. */
data class CalendarWrite(
    /** The body the facts are about, whether the ship already had it or this makes it. */
    val bodyId: String,
    val facts: Facts,
    /** Occurrence keys now written, so the next pass does not write them again. */
    val occurrenceKeys: List<String>,
    /** True when this write creates the body, so the caller knows a decision was made. */
    val creates: Boolean,
)

/** The occurrences of a window, grouped into the events they belong to. */
fun calendarSubjects(rows: List<CalendarRow>): List<CalendarSubject> =
    rows.filter { !it.isTask && it.name.isNotBlank() && it.r > it.l }
        .groupBy { it.cal to it.id }
        .map { (k, occ) ->
            val sorted = occ.sortedBy { it.l }
            CalendarSubject(
                cal = k.first,
                uid = k.second,
                title = sorted.first().name,
                // The calendar's own answer, or the same event having
                // turned up more than once in this window.
                repeats = sorted.any { it.repeats } || sorted.size > 1,
                occurrences = sorted,
            )
        }

/**
 * The plan for one subject.
 *
 * [decided] is the body this install already settled on, [hits] what
 * the ship answered for the title and the UID, and [written] the
 * occurrence keys already sent. A hit of kind activity or situation is
 * the event itself; only a miss makes a body.
 */
fun calendarWrite(
    subject: CalendarSubject,
    decided: String?,
    hits: List<ResolvedBody>,
    written: Set<String>,
    ourShip: String,
    nowMs: Long,
): CalendarWrite {
    val existing = decided ?: hits.firstOrNull { it.kind == "activity" || it.kind == "situation" }?.id
    val kind = existing?.substringBefore('/')
    return when {
        kind == "activity" -> occurrencesOn(existing!!, subject, written, nowMs)
        kind == "situation" -> onSituation(existing!!, subject, written, ourShip)
        subject.repeats -> newActivity(subject, written, ourShip, nowMs)
        else -> newSituation(subject, written, ourShip)
    }
}

/** An occurrence of something the ship already keeps as an activity: last, and next. */
private fun occurrencesOn(id: String, subject: CalendarSubject, written: Set<String>, nowMs: Long): CalendarWrite {
    val obs = mutableListOf<Obs>()
    val keys = mutableListOf<String>()
    for (row in subject.occurrences.filter { it.l <= nowMs }) {
        val key = occurrenceKey(subject, row)
        if (key in written) continue
        obs += Obs(id, "last", JsonPrimitive(isoUtc(row.l)), row.l, sourceKind = "calendar", sourceId = source(subject))
        keys += key
    }
    nextOf(subject, nowMs)?.let { (next, anchor) ->
        obs += Obs(id, "next", JsonPrimitive(isoUtc(next)), anchor, sourceKind = "calendar", sourceId = source(subject))
    }
    return CalendarWrite(id, Facts(observations = obs), keys, creates = false)
}

/** The same one-off seen again: its own facts, on the body the ship has. */
private fun onSituation(id: String, subject: CalendarSubject, written: Set<String>, ourShip: String): CalendarWrite {
    val row = subject.occurrences.last()
    val key = occurrenceKey(subject, row)
    if (key in written) return CalendarWrite(id, Facts(), emptyList(), creates = false)
    return CalendarWrite(id, Facts(observations = situationObs(id, subject, row)), listOf(key), creates = false)
}

/**
 * A repeating event the ship does not have: one activity, with the UID
 * and the title as aliases so the next client to ask resolves it, and
 * every occurrence so far as a `last` row at its own time.
 */
private fun newActivity(subject: CalendarSubject, written: Set<String>, ourShip: String, nowMs: Long): CalendarWrite {
    val id = activityIdFor(subject)
    val aliases = (listOf(subject.uid, subject.title, normalizeTitle(subject.title)) + subject.first.tags)
        .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    val body = OBody(id, name = subject.title, aliases = aliases)
    val asOf = subject.occurrences.lastOrNull { it.l <= nowMs }?.l ?: subject.first.l
    val obs = mutableListOf<Obs>()
    val keys = mutableListOf<String>()
    fun obs(attr: String, value: JsonElement, at: Long) =
        obs.add(Obs(id, attr, value, at, sourceKind = "calendar", sourceId = source(subject)))
    obs("cadence", JsonPrimitive(subject.first.kind), asOf)
    obs("schedule", JsonPrimitive(scheduleOf(subject)), asOf)
    obs("participants", buildJsonObject { put("ref", "person/me") }, asOf)
    if (subject.location.isNotBlank()) obs("location", JsonPrimitive(subject.location), asOf)
    for (row in subject.occurrences.filter { it.l <= nowMs }) {
        val key = occurrenceKey(subject, row)
        if (key in written) continue
        obs("last", JsonPrimitive(isoUtc(row.l)), row.l)
        keys += key
    }
    nextOf(subject, nowMs)?.let { (next, anchor) -> obs("next", JsonPrimitive(isoUtc(next)), anchor) }
    return CalendarWrite(id, Facts(listOf(body), obs), keys, creates = true)
}

/** A one-off the ship does not have: a situation, started and ended. */
private fun newSituation(subject: CalendarSubject, written: Set<String>, ourShip: String): CalendarWrite {
    val row = subject.occurrences.last()
    val id = situationIdFor(subject)
    val aliases = (listOf(subject.uid) + subject.first.tags).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    val body = OBody(id, name = subject.title, aliases = aliases)
    val key = occurrenceKey(subject, row)
    return CalendarWrite(id, Facts(listOf(body), situationObs(id, subject, row)), listOf(key), creates = true)
}

/**
 * What a one-off says about itself. No status: a situation is open
 * until something says closed, and the owner's retire pass closes it at
 * its end. An "open" row dated after a close reopens it, which is how a
 * late reminder reopened a trip that had been over for months.
 */
private fun situationObs(id: String, subject: CalendarSubject, row: CalendarRow): List<Obs> = buildList {
    fun obs(attr: String, value: JsonElement, at: Long) =
        add(Obs(id, attr, value, at, sourceKind = "calendar", sourceId = source(subject)))
    obs("started", JsonPrimitive(isoUtc(row.l)), row.l)
    obs("ended", JsonPrimitive(isoUtc(row.r)), row.r)
    obs("participants", buildJsonObject { put("ref", "person/me") }, row.l)
    if (row.location.isNotBlank()) obs("location", JsonPrimitive(row.location), row.l)
}

/**
 * The next occurrence and the time it became the next one: the end of
 * the one before it. An `at` in the future would not count until it
 * arrived, and the clock is not the event's own time, so the previous
 * occurrence's end is the honest anchor. A series with none behind it
 * has only today to stand on.
 */
private fun nextOf(subject: CalendarSubject, nowMs: Long): Pair<Long, Long>? {
    val next = subject.occurrences.firstOrNull { it.l > nowMs } ?: return null
    val previous = subject.occurrences.lastOrNull { it.l <= nowMs }
    return next.l to (previous?.r ?: dayOf(nowMs).second)
}

/** A readable rule for the schema's `schedule`, from what the calendar says. */
internal fun scheduleOf(subject: CalendarSubject): String {
    val kind = subject.first.kind
    val tags = subject.first.tags.firstOrNull()?.takeIf { it.isNotBlank() }
    return listOfNotNull(kind.takeIf { it.isNotBlank() && it != "once" } ?: "once", tags).joinToString(", ")
}

internal fun occurrenceKey(subject: CalendarSubject, row: CalendarRow): String =
    "occ:${subject.cal}/${subject.uid}/${row.l}"

private fun source(subject: CalendarSubject): String = "${subject.cal}/${subject.uid}"

internal fun activityIdFor(subject: CalendarSubject): String = calBodyId("activity", subject)

internal fun situationIdFor(subject: CalendarSubject): String = calBodyId("situation", subject)

private fun calBodyId(kind: String, subject: CalendarSubject): String {
    // The title, not the UID: a body people read in a list. The UID is
    // an alias, which is what the next client resolves against.
    val base = normalizeTitle(subject.title).ifBlank { subject.title }
    val slug = base.lowercase().replace(Regex("[^a-z0-9-]+"), "-").trim('-').replace(Regex("-{2,}"), "-")
    return "$kind/" + slug.take(60).trimEnd('-').ifBlank { "event" }
}
