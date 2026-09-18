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

/**
 * The people the ship keeps, by the first name each goes by. Built
 * from the state view, so a name in a title lands on the person the
 * ship already has rather than a second one by the same name.
 */
class EventPeople(
    private val byFirstName: Map<String, String>,
    /**
     * Whether the ship's own people were read this pass. Nobody is
     * created when they were not: a name that looks new against an
     * empty view is a twin of somebody the ship already has.
     */
    val known: Boolean = true,
) {
    fun idFor(name: String): String? = byFirstName[name.trim().lowercase()]

    /** The people named in [text] whom the ship already keeps. */
    fun named(text: String?): List<String> =
        WORDS.split((text ?: "").lowercase()).filter { it.length >= 2 }.mapNotNull { byFirstName[it] }.distinct()

    companion object {
        val NONE = EventPeople(emptyMap(), known = false)
        private val WORDS = Regex("[^a-z0-9]+")

        /** By the name and every alias a person body carries, a ship's @p aside. */
        fun of(bodies: List<KnownBody>): EventPeople {
            val out = mutableMapOf<String, String>()
            for (b in bodies.filter { it.id.startsWith("person/") }) {
                for (word in (listOfNotNull(b.name) + b.aliases)) {
                    if (word.startsWith("~") || word.isBlank()) continue
                    // putIfAbsent is the JVM's; common code cannot have it.
                    firstNameOf(word)?.let { if (it !in out) out[it] = b.id }
                }
            }
            return EventPeople(out)
        }
    }
}

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
     * The occurrences now written: each with the time it ends, so a
     * pass that finds one gone knows both times its rows were anchored
     * at, and whether what was written about it is settled.
     */
    val occurrences: List<Occurrence>,
    /** True when this write creates the body, so the caller knows a decision was made. */
    val creates: Boolean,
) {
    val occurrenceKeys: List<String> get() = occurrences.map { it.key }
}

/**
 * One occurrence this pass wrote about. [settled] is false while what
 * was written is only the schedule, which a later pass replaces with
 * what happened once the time has passed.
 */
data class Occurrence(val key: String, val endMs: Long, val settled: Boolean) {
    /** What the pass remembers, the end first so an older record still reads. */
    val record: String get() = "$endMs:" + if (settled) "f" else "s"

    companion object {
        /** Whether a remembered occurrence is still waiting to be said in the past tense. */
        fun unsettled(record: String): Boolean = record.substringAfter(':', "") == "s"

        fun endOf(record: String): Long? = record.substringBefore(':').toLongOrNull()
    }
}

/** What the calendar calls an event. A todo is not one. */
private val EVENT_CATS = setOf("timed", "allday", "date")

/** The occurrences of a window, grouped into the events they belong to. */
fun calendarSubjects(rows: List<CalendarRow>): List<CalendarSubject> =
    // By kind, not by what it is not: a todo is never an event, and
    // neither is anything else the calendar grows later.
    rows.filter { it.cat in EVENT_CATS && it.name.isNotBlank() && it.r > it.l }
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
    people: EventPeople = EventPeople.NONE,
): CalendarWrite {
    val existing = decided ?: hits.firstOrNull { it.kind == "activity" || it.kind == "situation" }?.id
    val kind = existing?.substringBefore('/')
    return when {
        kind == "activity" -> occurrencesOn(existing!!, subject, written, nowMs, changed, ours, people)
        kind == "situation" -> onSituation(existing!!, subject, written, changed, people, nowMs)
        subject.repeats -> newActivity(subject, written, ourShip, nowMs, ours, people)
        else -> newSituation(subject, written, ourShip, people, nowMs)
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
    people: EventPeople,
): CalendarWrite {
    val obs = mutableListOf<Obs>()
    val keys = mutableListOf<Occurrence>()
    if (changed) obs += activityContent(id, subject, nowMs, ours, people)
    for (row in subject.occurrences.filter { it.l <= nowMs }) {
        val key = occurrenceKey(subject, row)
        if (key in written) continue
        obs += Obs(id, "last", JsonPrimitive(isoUtc(row.l)), row.l, sourceKind = "calendar", sourceId = source(subject))
        keys += Occurrence(key, row.r, settled = true)
    }
    nextOf(subject, nowMs)?.let { (next, anchor, ends) ->
        obs += Obs(id, "next", JsonPrimitive(isoUtc(next)), anchor, untilMs = ends, sourceKind = "calendar", sourceId = source(subject))
    }
    return CalendarWrite(id, Facts(observations = obs), keys, creates = false)
}

/** The same one-off seen again: its own facts, on the body the ship has. */
private fun onSituation(
    id: String,
    subject: CalendarSubject,
    written: Set<String>,
    changed: Boolean,
    people: EventPeople,
    nowMs: Long,
): CalendarWrite {
    val row = subject.occurrences.last()
    val key = occurrenceKey(subject, row)
    if (key in written && !changed) return CalendarWrite(id, Facts(), emptyList(), creates = false)
    return CalendarWrite(
        id,
        Facts(observations = situationObs(id, subject, row, nowMs, people)),
        listOf(Occurrence(key, row.r, settled = row.r <= nowMs)),
        creates = false,
    )
}

/**
 * A repeating event the ship does not have: one activity, with the UID
 * and the title as aliases so the next client to ask resolves it, and
 * every occurrence so far as a `last` row at its own time.
 */
private fun newActivity(
    subject: CalendarSubject,
    written: Set<String>,
    ourShip: String,
    nowMs: Long,
    ours: Boolean,
    people: EventPeople,
): CalendarWrite {
    val id = activityIdFor(subject)
    val aliases = (listOf(subject.uid, subject.title, normalizeTitle(subject.title)) + subject.first.tags)
        .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    val body = OBody(id, name = subject.title, aliases = aliases)
    val obs = mutableListOf<Obs>()
    val keys = mutableListOf<Occurrence>()
    fun obs(attr: String, value: JsonElement, at: Long) =
        obs.add(Obs(id, attr, value, at, sourceKind = "calendar", sourceId = source(subject)))
    obs += activityContent(id, subject, nowMs, ours, people, create = true)
    for (row in subject.occurrences.filter { it.l <= nowMs }) {
        val key = occurrenceKey(subject, row)
        if (key in written) continue
        obs("last", JsonPrimitive(isoUtc(row.l)), row.l)
        keys += Occurrence(key, row.r, settled = true)
    }
    nextOf(subject, nowMs)?.let { (next, anchor, ends) ->
        obs.add(Obs(id, "next", JsonPrimitive(isoUtc(next)), anchor, untilMs = ends, sourceKind = "calendar", sourceId = source(subject)))
    }
    return CalendarWrite(id, Facts(listOf(body) + cast(subject, people, create = true).second, obs), keys, creates = true)
}

/** A one-off the ship does not have: a situation, started and ended. */
private fun newSituation(
    subject: CalendarSubject,
    written: Set<String>,
    ourShip: String,
    people: EventPeople,
    nowMs: Long,
): CalendarWrite {
    val row = subject.occurrences.last()
    val id = situationIdFor(subject)
    val aliases = (listOf(subject.uid) + subject.first.tags).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    val body = OBody(id, name = subject.title, aliases = aliases)
    val key = occurrenceKey(subject, row)
    return CalendarWrite(
        id,
        Facts(
            listOf(body) + cast(subject, people, create = true).second,
            situationObs(id, subject, row, nowMs, people, create = true),
        ),
        listOf(Occurrence(key, row.r, settled = row.r <= nowMs)),
        creates = true,
    )
}

/**
 * Everyone the event names. Its title is certain of some ("Adelaide-
 * Ballet/Tap", "Magnus Birthday"), and its leading word names a person
 * only where the ship already keeps one by that name, so a production,
 * a team or a place stays what it is. Its description names whoever the
 * ship already knows: "bring Linus's helmet" is Linus.
 *
 * A name the ship lacks is created, which is the one place this client
 * makes a person body without being told to by a contact. The ship's
 * own reconcile reads titles the same way.
 */
private fun cast(subject: CalendarSubject, people: EventPeople, create: Boolean): Pair<List<String>, List<OBody>> {
    val ids = mutableListOf<String>()
    val made = mutableListOf<OBody>()
    val (certain, lead) = namesInTitle(subject.title)
    for (name in certain) {
        val id = people.idFor(name)
        if (id != null) {
            ids += id
        } else if (create && people.known) {
            val slug = name.lowercase().replace(Regex("[^a-z0-9-]+"), "-").trim('-')
            if (slug.isNotEmpty()) {
                ids += "person/$slug"
                made += OBody("person/$slug", name = name)
            }
        }
    }
    if (certain.isEmpty() && lead != null) people.idFor(lead)?.let { ids += it }
    ids += people.named(subject.title)
    ids += people.named(subject.first.note)
    return ids.distinct().filter { it != "person/me" } to made.distinctBy { it.id }
}

/**
 * What an activity is, as against when it last happened: the rule it
 * runs on, where it is and that we are in it, true from the last time
 * it came round. Said again whenever the event itself changes.
 */
private fun activityContent(
    id: String,
    subject: CalendarSubject,
    nowMs: Long,
    ours: Boolean,
    people: EventPeople = EventPeople.NONE,
    create: Boolean = false,
): List<Obs> {
    val asOf = subject.occurrences.lastOrNull { it.l <= nowMs }?.l ?: subject.first.l
    return buildList {
        fun obs(attr: String, value: JsonElement, conf: Int = 100) =
            add(Obs(id, attr, value, asOf, conf = conf, sourceKind = "calendar", sourceId = source(subject)))
        obs("cadence", JsonPrimitive(subject.first.kind))
        obs("schedule", JsonPrimitive(scheduleOf(subject)))
        obs("participants", buildJsonObject { put("ref", "person/me") })
        for (who in cast(subject, people, create).first) {
            obs("participants", buildJsonObject { put("ref", who) }, conf = 85)
        }
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
        key to listOfNotNull(start, Occurrence.endOf(end))
    }.sortedBy { it.second.first() }
}

/** What to tell the ship about events that left the calendar, and which records are then done with. */
data class Vanished(val facts: Facts, val forget: List<String>)

/**
 * The events this install wrote that the calendar no longer keeps at
 * all. [written] is the `cal:` records, [occurrences] the `occ:` ones,
 * [kept] the `cal:` keys of every entry the calendar still has, near or
 * far (a window would lose an event moved past its edge).
 *
 * A one-off still ahead was cancelled: one `status: cancelled` row,
 * never a deleted body; the owner's retire pass prunes it in time. One
 * already behind is over, which is retire's to say. A series says
 * nothing: its `next` lapses on its own. Either way the uid is settled
 * and its records go, so it is said once.
 *
 * A calendar that went away as a whole is not evidence its events
 * were called off, so those are left alone.
 */
fun vanishedEvents(
    written: Map<String, String>,
    occurrences: Map<String, String>,
    kept: Set<String>,
    calendars: Set<String>,
    nowMs: Long,
): Vanished {
    val occByRef = occurrences.keys.groupBy { it.removePrefix("occ:").substringBeforeLast('/') }
    val cancel = mutableListOf<Obs>()
    val forget = mutableListOf<String>()
    for ((key, mark) in written) {
        if (!key.startsWith("cal:") || key in kept) continue
        val ref = key.removePrefix("cal:")
        if (ref.substringBefore('/') !in calendars) continue
        val occ = occByRef[ref].orEmpty()
        val body = mark.substringBefore('|')
        val ahead = occ.any { (it.substringAfterLast('/').toLongOrNull() ?: 0) > nowMs }
        if (body.startsWith("situation/") && ahead) {
            cancel += Obs(body, "status", JsonPrimitive("cancelled"), nowMs, sourceKind = "calendar", sourceId = ref)
        }
        forget += key
        forget += occ
    }
    return Vanished(Facts(observations = cancel), forget)
}

/**
 * What a one-off says about itself.
 *
 * A time ahead is a schedule and a time behind is a fact: `starts` and
 * `ends` say when it is meant to happen and are dated when we learned
 * it, because a row dated in the future is hidden until then, and
 * `started` and `ended` say what happened, each at its own moment.
 * Nothing is said in the past tense about something still ahead.
 *
 * No status either: a situation is open until something says closed,
 * and the owner's retire pass closes it at its end. An "open" row dated
 * after a close reopens it, which is how a late reminder reopened a
 * trip that had been over for months.
 */
private fun situationObs(
    id: String,
    subject: CalendarSubject,
    row: CalendarRow,
    nowMs: Long,
    people: EventPeople = EventPeople.NONE,
    create: Boolean = false,
): List<Obs> = buildList {
    fun obs(attr: String, value: JsonElement, at: Long, conf: Int = 100) =
        add(Obs(id, attr, value, at, conf = conf, sourceKind = "calendar", sourceId = source(subject)))
    if (row.l > nowMs) obs("starts", JsonPrimitive(isoUtc(row.l)), nowMs) else obs("started", JsonPrimitive(isoUtc(row.l)), row.l)
    if (row.r > nowMs) obs("ends", JsonPrimitive(isoUtc(row.r)), nowMs) else obs("ended", JsonPrimitive(isoUtc(row.r)), row.r)
    // Who is in it and where it is were learned when we read it, so on
    // something still ahead they are dated now rather than hidden until
    // the day arrives.
    val learned = minOf(row.l, nowMs)
    obs("participants", buildJsonObject { put("ref", "person/me") }, learned)
    for (who in cast(subject, people, create).first) {
        obs("participants", buildJsonObject { put("ref", who) }, learned, conf = 85)
    }
    if (row.location.isNotBlank()) obs("location", JsonPrimitive(row.location), learned)
}

/**
 * The next occurrence, the time it became the next one, and the time
 * it stops being it.
 *
 * The anchor is the end of the occurrence before it: an `at` in the
 * future would not count until it arrived, and the clock is not the
 * event's own time. The expiry is the occurrence's own end, so a next
 * that has happened stops standing without anybody coming back to
 * retract it. A series with none behind it has only today to stand on.
 */
private fun nextOf(subject: CalendarSubject, nowMs: Long): Triple<Long, Long, Long>? {
    val next = subject.occurrences.firstOrNull { it.l > nowMs } ?: return null
    val previous = subject.occurrences.lastOrNull { it.l <= nowMs }
    return Triple(next.l, previous?.r ?: dayOf(nowMs).second, next.r)
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
