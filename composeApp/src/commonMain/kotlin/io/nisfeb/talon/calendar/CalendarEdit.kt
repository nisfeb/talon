package io.nisfeb.talon.calendar

import io.nisfeb.talon.util.nowMs
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** The four shapes an event can have, in the calendar's own words. */
enum class EventCat(val wire: String, val label: String) {
    TIMED("timed", "At a time"),
    ALLDAY("allday", "All day"),
    DATE("date", "A date each year"),
    TODO("todo", "Task"),
}

/** The recurrence kinds the editor offers; the calendar has more. */
enum class Repeat(val kind: String, val label: String) {
    ONCE("once", "Once"),
    DAILY("daily", "Daily"),
    WEEKLY("weekly", "Weekly"),
    MONTHLY("monthly", "Monthly"),
    MONTHLY_NTH("monthly-nth", "Monthly, a weekday"),
    YEARLY("yearly", "Yearly"),
    EVERY("every", "Every so many minutes"),
}

/** The ordinals monthly-nth takes, in the calendar's words. */
val ORDINALS = listOf("first", "second", "third", "fourth", "last")

/** How an edit of a repeating event applies. */
enum class EditScope(val label: String) {
    ALL("Every occurrence"),
    ONLY("This one only"),
    FOLLOWING("This and following"),
}

/** What the editor holds; [eventBody] turns it into what the calendar takes. */
data class EventDraft(
    val name: String = "",
    val note: String = "",
    val location: String = "",
    val cal: String? = null,
    val cat: EventCat = EventCat.TIMED,
    val date: LocalDate,
    val minuteOfDay: Int = 9 * 60,
    val durMin: Int = 60,
    val spanDays: Int = 1,
    val repeat: Repeat = Repeat.ONCE,
    val weekdays: Set<DayOfWeek> = emptySet(),
    /** Occurrences, 0 for no limit. */
    val count: Int = 0,
    val until: LocalDate? = null,
    /** A zone name, or null for the calendar's own. */
    val zone: String? = null,
    /** iCalendar CATEGORIES; other clients show them as categories. */
    val tags: List<String> = emptyList(),
    /** monthly-nth: which weekday of the month, and which one. */
    val ordinal: String = "first",
    val nthDay: DayOfWeek? = null,
    /** every: the period, in minutes. */
    val periodMin: Int = 60,
    /**
     * A rule the editor does not model (an imported rrule or cron): the
     * kind, its arguments and its anchor are kept verbatim, so the rest
     * of the event can still be edited without rewriting the rule.
     */
    val rawKind: String? = null,
    val rawArgs: JsonObject? = null,
    val rawStartMs: Long? = null,
    /** A task: when it is due, if ever, and whether it is done. The
     *  done moment is kept across an edit rather than reset to now. */
    val due: LocalDate? = null,
    val done: Boolean = false,
    val doneMs: Long? = null,
    /** "#rrggbb", or blank for the calendar's own colour. */
    val color: String = "",
    /**
     * Whatever else the entry carries that this editor does not show:
     * an orrery task's link back to its action, a field another client
     * wrote. Sent back as it came. Without it, saving an edit rebuilt
     * the entry from the fields above and dropped the rest.
     */
    val otherMeta: JsonObject = JsonObject(emptyMap()),
) {
    val repeats: Boolean get() = cat != EventCat.TODO && cat != EventCat.DATE && (rawKind != null || repeat != Repeat.ONCE)
}

private fun LocalDate.utcMidnightMs() = atTime(0, 0).toInstant(TimeZone.UTC).toEpochMilliseconds()

/** The tick poke: done-event, the way the calendar's page sends it. */
fun doneBody(id: String, done: Boolean): JsonObject = buildJsonObject {
    put("action", "done-event")
    put("id", id)
    put("done", done)
}

/** Take an event or a todo away. */
fun deleteBody(id: String): JsonObject = buildJsonObject {
    put("action", "del-event")
    put("id", id)
}

/** "work, family" -> ["work", "family"]: trimmed, blanks and repeats dropped. */
fun parseTags(text: String): List<String> =
    text.split(',').map { it.trim().trimStart('#') }.filter { it.isNotEmpty() }.distinct()

private val WIRE_DAYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

/**
 * The poke for [d]: add-event, or edit-event of [id]. Mirrors the
 * calendar's own page: wall-clock fields are sent as if UTC and the
 * calendar reads them in the event's zone; a repeating timed event
 * anchors on the day and carries its time in the rule's arguments.
 */
fun eventBody(d: EventDraft, id: String? = null): JsonObject = buildJsonObject {
    put("action", if (id == null) "add-event" else "edit-event")
    if (id != null) put("id", id)
    put("cat", d.cat.wire)
    putJsonObject("meta") {
        d.otherMeta.forEach { (k, v) -> put(k, v) }
        put("name", d.name.trim())
        if (d.color.isNotBlank()) put("color", d.color.trim())
        if (d.note.isNotBlank()) put("note", d.note.trim())
        if (d.location.isNotBlank()) put("location", d.location.trim())
        if (d.tags.isNotEmpty()) put("tags", JsonArray(d.tags.map { JsonPrimitive(it) }))
    }
    d.cal?.let { put("cal", it) }
    if (d.cat == EventCat.TODO) {
        d.due?.let { put("due_ms", it.utcMidnightMs()) }
        if (d.done) put("done_ms", d.doneMs ?: nowMs())
        return@buildJsonObject
    }
    if (d.cat == EventCat.DATE) {
        put("month", d.date.monthNumber)
        put("day", d.date.dayOfMonth)
        return@buildJsonObject
    }
    if (d.rawKind != null) {
        // An imported rule, sent back as it came.
        put("kind", d.rawKind)
        put("start_ms", d.rawStartMs ?: d.date.atTime(0, 0).toInstant(TimeZone.UTC).toEpochMilliseconds())
        put("args", d.rawArgs ?: JsonObject(emptyMap()))
    } else {
        put("kind", d.repeat.kind)
        val wall = d.date.atTime(LocalTime(d.minuteOfDay / 60, d.minuteOfDay % 60))
        // once and every anchor on the moment; the grid kinds on the day,
        // with the time in their arguments.
        val anchorsOnTime = d.cat == EventCat.TIMED && (d.repeat == Repeat.ONCE || d.repeat == Repeat.EVERY)
        put("start_ms", (if (anchorsOnTime) wall else d.date.atTime(0, 0)).toInstant(TimeZone.UTC).toEpochMilliseconds())
        putJsonObject("args") {
            if (d.cat == EventCat.TIMED && d.repeat != Repeat.ONCE && d.repeat != Repeat.EVERY) put("at", d.minuteOfDay)
            when (d.repeat) {
                Repeat.WEEKLY -> put("days", JsonArray(d.weekdays.sortedBy { it.isoDayNumber }.map { JsonPrimitive(WIRE_DAYS[it.isoDayNumber - 1]) }))
                Repeat.MONTHLY -> put("day", d.date.dayOfMonth)
                Repeat.MONTHLY_NTH -> {
                    put("ord", d.ordinal.takeIf { it in ORDINALS } ?: "first")
                    put("day", WIRE_DAYS[(d.nthDay ?: d.date.dayOfWeek).isoDayNumber - 1])
                }
                Repeat.YEARLY -> { put("month", d.date.monthNumber); put("day", d.date.dayOfMonth) }
                Repeat.EVERY -> put("period", d.periodMin.coerceAtLeast(1))
                else -> Unit
            }
        }
    }
    if (d.cat == EventCat.TIMED) {
        d.zone?.let { put("zone", it) }
        put("fin", "dur")
        put("dur_min", d.durMin.coerceAtLeast(0))
    } else {
        put("span_days", d.spanDays.coerceAtLeast(1))
    }
    if (d.rawKind == null && d.repeat != Repeat.ONCE) {
        if (d.count > 0) put("count", d.count)
        else d.until?.let { put("until_ms", it.plus(1, DateTimeUnit.DAY).atTime(0, 0).toInstant(TimeZone.UTC).toEpochMilliseconds()) }
    }
}

/**
 * The event this occurrence becomes on its own: a one-off at the
 * occurrence's own day and time, the rest as edited. Sent after a
 * skip-event for the occurrence, the way the calendar's page does it.
 */
fun onlyBody(d: EventDraft, occurrence: LocalDateTime): JsonObject = eventBody(
    d.copy(
        repeat = Repeat.ONCE, rawKind = null, rawArgs = null, rawStartMs = null, count = 0, until = null,
        date = occurrence.date,
        minuteOfDay = if (d.cat == EventCat.TIMED) occurrence.hour * 60 + occurrence.minute else d.minuteOfDay,
    ),
)

/** The series as edited, restarted from the occurrence's day. Sent
 *  after a cap-event that ends the old series before it. */
fun followingBody(d: EventDraft, occurrence: LocalDateTime): JsonObject =
    eventBody(d.copy(date = occurrence.date, rawStartMs = d.rawStartMs?.let { occurrence.date.atTime(0, 0).toInstant(TimeZone.UTC).toEpochMilliseconds() }))

/** The editor's draft for an event.json answer, or null for a shape it cannot edit. */
/** The meta fields the editor shows and writes itself. Everything else rides through. */
private val EDITED_META = setOf("name", "note", "location", "tags", "color")

/**
 * A task's draft from the row the screen already has: its calendar,
 * meta, tick and due day, the day being midnight in [zone] as the feed
 * gives it. A task has no rule to read, so its editor needs nothing
 * from the ship and opens at once; it waited on a read that queued
 * behind everything else the ship was doing. Only the time a ticked
 * task was done is missing (the list does not carry it): saving reads it.
 */
fun taskDraft(r: CalendarRow, zone: TimeZone, today: LocalDate): EventDraft? = draftFromEvent(
    kotlinx.serialization.json.buildJsonObject {
        put("cat", "todo")
        put("cal", r.cal)
        put("meta", r.meta)
        put("done", r.done)
        if (r.l > 0) {
            val due = Instant.fromEpochMilliseconds(r.l).toLocalDateTime(zone).date
            put("due_ms", due.atTime(0, 0).toInstant(TimeZone.UTC).toEpochMilliseconds())
        }
    },
    today,
)

fun draftFromEvent(e: JsonObject, today: LocalDate): EventDraft? {
    fun str(k: String) = e[k]?.jsonPrimitive?.contentOrNull
    fun num(k: String) = e[k]?.jsonPrimitive?.intOrNull
    val meta = e["meta"] as? JsonObject
    fun metaStr(k: String) = meta?.get(k)?.jsonPrimitive?.contentOrNull.orEmpty()
    val cat = EventCat.entries.firstOrNull { it.wire == str("cat") } ?: return null
    val base = EventDraft(
        name = metaStr("name"), note = metaStr("note"), location = metaStr("location"),
        cal = str("cal"), cat = cat, date = today,
        tags = (meta?.get("tags") as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
        color = metaStr("color"),
        otherMeta = JsonObject(meta.orEmpty().filterKeys { it !in EDITED_META }),
    )
    if (cat == EventCat.TODO) {
        val due = e["due_ms"]?.jsonPrimitive?.longOrNull?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date }
        return base.copy(
            date = due ?: today, due = due,
            done = e["done"]?.jsonPrimitive?.booleanOrNull ?: false,
            doneMs = e["done_ms"]?.jsonPrimitive?.longOrNull,
        )
    }
    if (cat == EventCat.DATE) {
        val m = num("month") ?: return null
        val d = num("day") ?: return null
        return base.copy(date = runCatching { LocalDate(today.year, m, d) }.getOrElse { return null })
    }
    val kind = str("kind") ?: return null
    val startMs = e["start_ms"]?.jsonPrimitive?.longOrNull ?: return null
    val wall = Instant.fromEpochMilliseconds(startMs).toLocalDateTime(TimeZone.UTC)
    val args = e["args"] as? JsonObject
    val zone = str("zone")?.takeIf { it != "none" }
    val common = base.copy(
        date = wall.date,
        durMin = num("dur_min") ?: 60,
        spanDays = num("span_days") ?: 1,
        count = num("count") ?: 0,
        // Written as midnight of the day after the last occurrence;
        // read back as the last day itself.
        until = e["until_ms"]?.jsonPrimitive?.longOrNull?.let {
            Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date.minus(1, DateTimeUnit.DAY)
        },
        zone = zone,
    )
    val repeat = Repeat.entries.firstOrNull { it.kind == kind }
        // A rule the form does not model is kept whole: the rest of the
        // event stays editable.
        ?: return common.copy(rawKind = kind, rawArgs = args, rawStartMs = startMs, minuteOfDay = wall.hour * 60 + wall.minute)
    val at = args?.get("at")?.jsonPrimitive?.intOrNull
    val days = (args?.get("days") as? JsonArray)?.mapNotNull { j ->
        WIRE_DAYS.indexOf(j.jsonPrimitive.contentOrNull).takeIf { it >= 0 }?.let { DayOfWeek(it + 1) }
    }.orEmpty().toSet()
    val anchorsOnTime = cat == EventCat.TIMED && (repeat == Repeat.ONCE || repeat == Repeat.EVERY)
    return common.copy(
        // A shared calendar's args come from a peer; an out-of-range at
        // would crash the time picker or throw on save.
        minuteOfDay = if (anchorsOnTime) wall.hour * 60 + wall.minute else at?.coerceIn(0, 1439) ?: 0,
        repeat = repeat,
        weekdays = days,
        ordinal = args?.get("ord")?.jsonPrimitive?.contentOrNull?.takeIf { it in ORDINALS } ?: "first",
        nthDay = WIRE_DAYS.indexOf(args?.get("day")?.jsonPrimitive?.contentOrNull).takeIf { it >= 0 }?.let { DayOfWeek(it + 1) },
        periodMin = args?.get("period")?.jsonPrimitive?.intOrNull ?: 60,
    )
}

/**
 * When a row starts and ends as moments in [zone]: a timed row's own,
 * an all-day row's days translated from date-space to the zone's
 * midnights, so "under way" and "today" mean the reader's day.
 */
fun CalendarRow.bounds(zone: TimeZone): Pair<Long, Long> {
    if (!all) return l to r
    // Straight from the moments rather than through daysOf, whose cap
    // on the days shown would shorten a long span's end.
    val first = Instant.fromEpochMilliseconds(l).toLocalDateTime(TimeZone.UTC).date
    val last = Instant.fromEpochMilliseconds(r - 1).toLocalDateTime(TimeZone.UTC).date
    val start = first.atTime(0, 0).toInstant(zone).toEpochMilliseconds()
    val end = (if (last < first) first else last).plus(1, DateTimeUnit.DAY).atTime(0, 0).toInstant(zone).toEpochMilliseconds()
    return start to end
}

/**
 * The occurrence a scoped edit ("this one only", "this and following")
 * anchors on. An all-day row's start is date-space -- midnight UTC --
 * and is read as UTC whatever the display zone, or the write lands on
 * the day before in a zone behind UTC; a timed row's is a moment, read
 * in [zone].
 */
fun occurrenceAt(startMs: Long, allDay: Boolean, zone: TimeZone): LocalDateTime =
    Instant.fromEpochMilliseconds(startMs).toLocalDateTime(if (allDay) TimeZone.UTC else zone)

/**
 * A shared event, given as moments, as a draft for this ship's calendar.
 * A range of whole UTC days is a day event, the calendar's date-space;
 * anything else is timed, read in [zone] and named as [zoneId] so the
 * calendar reads the wall clock the same way.
 */
fun sharedDraft(title: String, startMs: Long, endMs: Long, cal: String?, zone: TimeZone, zoneId: String?): EventDraft {
    val day = 86_400_000L
    if (endMs > startMs && startMs % day == 0L && endMs % day == 0L) {
        return EventDraft(
            name = title, cal = cal, cat = EventCat.ALLDAY,
            date = Instant.fromEpochMilliseconds(startMs).toLocalDateTime(TimeZone.UTC).date,
            spanDays = ((endMs - startMs) / day).toInt(),
        )
    }
    val wall = Instant.fromEpochMilliseconds(startMs).toLocalDateTime(zone)
    return EventDraft(
        name = title, cal = cal, cat = EventCat.TIMED, date = wall.date,
        minuteOfDay = wall.hour * 60 + wall.minute,
        durMin = ((endMs - startMs) / 60_000L).toInt().coerceAtLeast(0),
        zone = zoneId,
    )
}

/** The six weeks a month view shows, Monday first, 42 days. */
fun monthGrid(year: Int, month: Int): List<LocalDate> {
    val first = LocalDate(year, month, 1)
    val start = first.minus(first.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
    return List(42) { start.plus(it, DateTimeUnit.DAY) }
}

/**
 * Every day a row touches. A timed row's moments are read in [zone];
 * an all-day row (a date, a task's due day, a span of days) is kept
 * by the calendar in date-space, midnight UTC to midnight UTC, and
 * is read as UTC whatever the zone, as the calendar's page does.
 */
fun daysOf(row: CalendarRow, zone: TimeZone): List<LocalDate> {
    val z = if (row.all) TimeZone.UTC else zone
    val first = Instant.fromEpochMilliseconds(row.l).toLocalDateTime(z).date
    val last = Instant.fromEpochMilliseconds(row.r - 1).toLocalDateTime(z).date
    if (last < first) return listOf(first)
    return generateSequence(first) { d -> d.plus(1, DateTimeUnit.DAY).takeIf { it <= last } }.take(62).toList()
}
