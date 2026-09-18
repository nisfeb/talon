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

    /**
     * The event as against its times: what the ship was told that an
     * edit would make wrong. The note is in it although Talon never
     * sends text, so a rewritten description still runs the event
     * through the pass again rather than resting on a stale answer.
     */
    val digest: String get() =
        listOf(title, first.note, first.location, first.kind, first.tags.sorted().joinToString(","))
            .joinToString("|").hashCode().toString(16)
}

/** What to write for one subject, and what to remember afterwards. */
data class CalendarWrite(
    /** The body the facts are about, whether the ship already had it or this makes it. */
    val bodyId: String,
    val facts: Facts,
    /**
     * The occurrences now written, each with the time it ends, so the
     * next pass does not write them again and a pass that finds one
     * gone knows both times its rows were anchored at.
     */
    val occurrences: List<Pair<String, Long>>,
    /** True when this write creates the body, so the caller knows a decision was made. */
    val creates: Boolean,
) {
    val occurrenceKeys: List<String> get() = occurrences.map { it.first }
}

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
 *
 * [changed] says the event is not what it was when those keys were
 * written: a new time, place or description. Then what the event is
 * gets said again, at the anchor it is true from, and a row at the same
 * time with the same value is the same observation to the ship.
 *
 * [ours] says the event sits on a calendar this ship keeps itself,
 * which is the only case where the person whose orrery this is can be
 * named as the one who holds it. On a calendar another ship shares,
 * whose event it is is not ours to say.
 */
fun calendarWrite(
    subject: CalendarSubject,
    decided: String?,
    hits: List<ResolvedBody>,
    written: Set<String>,
    ourShip: String,
    nowMs: Long,
    changed: Boolean = false,
    ours: Boolean = false,
): CalendarWrite {
    val existing = decided ?: hits.firstOrNull { it.kind == "activity" || it.kind == "situation" }?.id
    val kind = existing?.substringBefore('/')
    return when {
        kind == "activity" -> occurrencesOn(existing!!, subject, written, nowMs, changed, ours)
        kind == "situation" -> onSituation(existing!!, subject, written, changed)
        subject.repeats -> newActivity(subject, written, ourShip, nowMs, ours)
        else -> newSituation(subject, written, ourShip)
    }
}

/** An occurrence of something the ship already keeps as an activity: last, and next. */
private fun occurrencesOn(
    id: String,
    subject: CalendarSubject,
    written: Set<String>,
    nowMs: Long,
    changed: Boolean,
    ours: Boolean,
): CalendarWrite {
    val obs = mutableListOf<Obs>()
    val keys = mutableListOf<Pair<String, Long>>()
    if (changed) obs += activityContent(id, subject, nowMs, ours)
    for (row in subject.occurrences.filter { it.l <= nowMs }) {
        val key = occurrenceKey(subject, row)
        if (key in written) continue
        obs += Obs(id, "last", JsonPrimitive(isoUtc(row.l)), row.l, sourceKind = "calendar", sourceId = source(subject))
        keys += key to row.r
    }
    nextOf(subject, nowMs)?.let { (next, anchor) ->
        obs += Obs(id, "next", JsonPrimitive(isoUtc(next)), anchor, sourceKind = "calendar", sourceId = source(subject))
    }
    return CalendarWrite(id, Facts(observations = obs), keys, creates = false)
}

/** The same one-off seen again: its own facts, on the body the ship has. */
private fun onSituation(id: String, subject: CalendarSubject, written: Set<String>, changed: Boolean): CalendarWrite {
    val row = subject.occurrences.last()
    val key = occurrenceKey(subject, row)
    if (key in written && !changed) return CalendarWrite(id, Facts(), emptyList(), creates = false)
    return CalendarWrite(id, Facts(observations = situationObs(id, subject, row)), listOf(key to row.r), creates = false)
}

/**
 * A repeating event the ship does not have: one activity, with the UID
 * and the title as aliases so the next client to ask resolves it, and
 * every occurrence so far as a `last` row at its own time.
 */
private fun newActivity(subject: CalendarSubject, written: Set<String>, ourShip: String, nowMs: Long, ours: Boolean): CalendarWrite {
    val id = activityIdFor(subject)
    val aliases = (listOf(subject.uid, subject.title, normalizeTitle(subject.title)) + subject.first.tags)
        .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    val body = OBody(id, name = subject.title, aliases = aliases)
    val obs = mutableListOf<Obs>()
    val keys = mutableListOf<Pair<String, Long>>()
    fun obs(attr: String, value: JsonElement, at: Long) =
        obs.add(Obs(id, attr, value, at, sourceKind = "calendar", sourceId = source(subject)))
    obs += activityContent(id, subject, nowMs, ours)
    for (row in subject.occurrences.filter { it.l <= nowMs }) {
        val key = occurrenceKey(subject, row)
        if (key in written) continue
        obs("last", JsonPrimitive(isoUtc(row.l)), row.l)
        keys += key to row.r
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
    return CalendarWrite(id, Facts(listOf(body), situationObs(id, subject, row)), listOf(key to row.r), creates = true)
}

/**
 * What an activity is, as against when it last happened: the rule it
 * runs on, where it is and that we are in it, true from the last time
 * it came round. Said again whenever the event itself changes.
 */
private fun activityContent(id: String, subject: CalendarSubject, nowMs: Long, ours: Boolean): List<Obs> {
    val asOf = subject.occurrences.lastOrNull { it.l <= nowMs }?.l ?: subject.first.l
    return buildList {
        fun obs(attr: String, value: JsonElement) =
            add(Obs(id, attr, value, asOf, sourceKind = "calendar", sourceId = source(subject)))
        obs("cadence", JsonPrimitive(subject.first.kind))
        obs("schedule", JsonPrimitive(scheduleOf(subject)))
        obs("participants", buildJsonObject { put("ref", "person/me") })
        // Only for a calendar this ship keeps: on one another ship
        // shares, whose activity it is is that ship's to say.
        if (ours) obs("organizer", buildJsonObject { put("ref", "person/me") })
        if (subject.location.isNotBlank()) obs("location", JsonPrimitive(subject.location))
    }
}

/**
 * The occurrences this install wrote that the calendar no longer holds,
 * within the window it can see: each as the key it was remembered under
 * and every time its rows were anchored at, which is its start and,
 * where the pass that wrote it said so, its end. An event moved to a
 * new time leaves its old rows standing, and the ship keeps the latest
 * `at` of the rows it has, so a meeting moved earlier would go on
 * reading as the time it used to be at. Outside the window there is
 * nothing to compare against, so nothing is judged stale there.
 */
fun staleOccurrences(
    subject: CalendarSubject,
    written: Map<String, String>,
    fromMs: Long,
    toMs: Long,
): List<Pair<String, List<Long>>> {
    val prefix = "occ:${subject.cal}/${subject.uid}/"
    val here = subject.occurrences.map { it.l }.toSet()
    return written.mapNotNull { (key, end) ->
        if (!key.startsWith(prefix)) return@mapNotNull null
        val start = key.removePrefix(prefix).toLongOrNull() ?: return@mapNotNull null
        if (start in here || start !in fromMs..toMs) return@mapNotNull null
        key to listOfNotNull(start, end.toLongOrNull())
    }.sortedBy { it.second.first() }
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
