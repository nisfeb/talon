package io.nisfeb.talon.orrery

import io.nisfeb.talon.calendar.CalendarTask
import io.nisfeb.talon.calendar.metaStr
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * An orrery task is a todo in the calendar.
 *
 * The analyst files a task and stops. The owner should see it where
 * they see everything else they have to do, which is the calendar's
 * task list, on the ship, in Thunderbird and on the phone. Talon reads
 * the calendar already, so Talon is what keeps the two in step.
 *
 * The link between them is kept in the todo itself, under `meta.orrery`
 * (the calendar passes a field it does not know through untouched), and
 * never in this install's memory. That is what makes starting again
 * from nothing safe: the calendar says which todos are orrery's.
 */

/** What the mirror decides to do, in the calendar or on the ship. */
sealed interface TaskMove {
    /** Make the todo an action wants. */
    data class Make(val action: OrreryAction) : TaskMove

    /** Tick the todo: the ship says the action is done. */
    data class Tick(val todoId: String) : TaskMove

    /** Take the todo away: the owner said no to the action. */
    data class Drop(val todoId: String) : TaskMove

    /** Tell the ship the action is done: the owner ticked its todo. */
    data class Report(val actionId: String) : TaskMove

    /** File a todo the owner typed as an approved task, then link it. */
    data class Adopt(val todo: CalendarTask) : TaskMove

    /** Link a todo the owner typed to the open task it already is, approving it if it was only proposed. */
    data class Link(val todo: CalendarTask, val actionId: String, val approve: Boolean) : TaskMove

    /** Dismiss a task the owner typed and has since deleted from the calendar. */
    data class Withdraw(val actionId: String) : TaskMove

    /** Put an approved calendar action on the calendar, then tell the ship it is done. */
    data class Place(val action: OrreryAction) : TaskMove

    /** Its event is on the calendar already: a pass died before telling the ship. */
    data class Placed(val actionId: String) : TaskMove

    /** Approved, with no time to put on a calendar. */
    data class Unplaceable(val actionId: String) : TaskMove
}

/** What a task filed from a todo says about itself: the owner wrote it in the calendar. */
const val TYPED_IN_CALENDAR = "typed in the calendar"

/** Whether an action was filed from a todo the owner typed. */
fun OrreryAction.typedInCalendar(): Boolean =
    (payload["why"] as? JsonPrimitive)?.contentOrNull == TYPED_IN_CALENDAR

/** The action a todo was made for, when it was made for one. */
fun CalendarTask.orreryAction(): String? = meta.metaStr("orrery").takeIf { it.isNotBlank() }

/** Whether the calendar row is one of orrery's own, whatever shape the link takes. */
fun CalendarTask.isOrrerys(): Boolean =
    orreryAction() != null || id.startsWith("orrery-") || tags.any { it.equals("orrery", ignoreCase = true) }

/**
 * Which actions are the owner's to do: one the ship has approved, or
 * one an executor has claimed. A proposal is still a question and
 * waits in the tray rather than the task list.
 */
private val LIVE = setOf("approved", "claimed")
private val OPEN = LIVE + "proposed"
private val REFUSED = setOf("dismissed", "failed")

/** A todo's due and an action's due fall on one day, or neither has one. */
private fun sameDue(due: String?, dueMs: Long?): Boolean {
    if (due == null || dueMs == null) return due == null && dueMs == null
    val a = runCatching { kotlinx.datetime.Instant.parse(due) }.getOrNull() ?: return false
    val utc = kotlinx.datetime.TimeZone.UTC
    return a.toLocalDateTime(utc).date == kotlinx.datetime.Instant.fromEpochMilliseconds(dueMs).toLocalDateTime(utc).date
}

/**
 * What to do so that the ship and the calendar agree.
 *
 * Nothing here reopens anything: orrery's decisions are final, so a
 * todo unticked after the action was done says nothing, and once the
 * two sides agree there is nothing to do at all.
 */
fun taskMoves(actions: List<OrreryAction>, todos: List<CalendarTask>): List<TaskMove> {
    val tasks = actions.filter { it.kind == "task" }
    // Two installs passing at once can each make the todo. The first
    // ticked one, else the first, is the todo; the rest go.
    val linked = todos.filter { it.orreryAction() != null }
        .groupBy { it.orreryAction()!! }
        .mapValues { (_, ts) -> ts.sortedByDescending { it.done } }
    val byAction = linked.mapValues { it.value.first() }
    val out = mutableListOf<TaskMove>()
    linked.values.forEach { ts -> ts.drop(1).forEach { out += TaskMove.Drop(it.id) } }
    // A todo the owner typed is a task the owner approved. One whose name
    // and due match an open task nobody's todo carries is that task, filed
    // by a pass that died before it could link: link it, do not file a
    // twin. A ticked one is history and is left alone.
    val free = tasks.filter { it.status in OPEN && it.id !in byAction }.toMutableList()
    val adopted = mutableSetOf<String>()
    for (t in todos.filter { it.cat == "todo" && !it.done && !it.isOrrerys() && it.name.isNotBlank() }) {
        val match = free.firstOrNull { it.title.trim() == t.name.trim() && sameDue(it.due, t.dueMs) }
        if (match != null) {
            free.remove(match)
            adopted += match.id
            out += TaskMove.Link(t, match.id, approve = match.status == "proposed")
        } else {
            out += TaskMove.Adopt(t)
        }
    }
    for (a in tasks) {
        val todo = byAction[a.id]
        when {
            a.id in adopted -> Unit
            // The owner wrote it and has since taken it off the calendar.
            a.status in LIVE && todo == null && a.typedInCalendar() -> out += TaskMove.Withdraw(a.id)
            a.status in LIVE && todo == null -> out += TaskMove.Make(a)
            a.status == "done" && todo != null && !todo.done -> out += TaskMove.Tick(todo.id)
            a.status in REFUSED && todo != null -> out += TaskMove.Drop(todo.id)
            // The owner ticked it where they saw it. A claim is for the
            // executor that does the work; a tick is the owner saying it
            // is done, so it needs no claim of its own.
            a.status in LIVE && todo != null && todo.done -> out += TaskMove.Report(a.id)
        }
    }
    return out
}

/**
 * The calendar poke that makes an action's todo.
 *
 * `meta.orrery` is the link back. The calendar does not know the field
 * and passes it through untouched, which is what lets a pass that has
 * forgotten everything find the todo it made last time.
 */
fun todoBody(a: OrreryAction): kotlinx.serialization.json.JsonObject = buildJsonObject {
    put("action", "add-event")
    put("cat", "todo")
    putJsonObject("meta") {
        put("name", a.title.ifBlank { "Something to do" })
        val why = listOfNotNull(
            a.payload["why"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            a.payload["notes"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
        ).joinToString("\n\n")
        if (why.isNotBlank()) put("note", why)
        putJsonArray("tags") { add(JsonPrimitive("orrery")) }
        put("orrery", a.id)
    }
    a.due?.let { due ->
        runCatching { kotlinx.datetime.Instant.parse(due).toEpochMilliseconds() }
            .getOrNull()?.let { put("due_ms", it) }
    }
}

/** The action a todo the owner typed becomes: its name, its due, its note, and where it came from. */
fun adoptBody(t: CalendarTask): kotlinx.serialization.json.JsonObject = buildJsonObject {
    put("kind", "task")
    put("title", t.name.trim().take(200))
    putJsonArray("about") {}
    t.dueMs?.let { put("due", isoUtc(it)) }
    putJsonObject("payload") {
        if (t.note.isNotBlank()) put("notes", t.note.trim())
        put("why", TYPED_IN_CALENDAR)
    }
}

/**
 * The edit that links a todo to its action: the whole todo as it is,
 * with `meta.orrery` added. From then on it is a mirrored todo.
 */
fun linkBody(t: CalendarTask, actionId: String): kotlinx.serialization.json.JsonObject = buildJsonObject {
    put("action", "edit-event")
    put("id", t.id)
    put("cat", "todo")
    put("meta", kotlinx.serialization.json.JsonObject(t.meta + ("orrery" to JsonPrimitive(actionId))))
    put("cal", t.cal)
    t.dueMs?.let { put("due_ms", it) }
}

/** What a calendar action placed on the calendar says when it is done. */
const val ON_THE_CALENDAR = "on the calendar"

/**
 * An approved calendar action is an event on the calendar: rule 11 of
 * orrery-utils' client guide. The event carries the action's id under
 * `meta.orrery`, which is how a pass that died between making it and
 * saying so finds it, and two installs placing at once keep one. A
 * dismissed action makes nothing, and a claimed one is an executor's.
 */
fun calendarMoves(actions: List<OrreryAction>, events: List<CalendarTask>): List<TaskMove> {
    val linked = events.filter { it.cat != "todo" && it.orreryAction() != null }
        .groupBy { it.orreryAction()!! }
        .mapValues { (_, es) -> es.sortedBy { it.id } }
    val out = mutableListOf<TaskMove>()
    linked.values.forEach { es -> es.drop(1).forEach { out += TaskMove.Drop(it.id) } }
    for (a in actions.filter { it.kind == "calendar" && it.status == "approved" }) {
        out += when {
            a.id in linked -> TaskMove.Placed(a.id)
            a.eventToAdd() == null -> TaskMove.Unplaceable(a.id)
            else -> TaskMove.Place(a)
        }
    }
    return out
}

/**
 * The add-event for an approved calendar action: its title and times,
 * all day when both ends fall on midnight here, or it gave a bare date,
 * else at its time in [zone]; `meta.orrery` the action, and the
 * orrery tag. Without an end a timed event is an hour.
 */
fun placeBody(a: OrreryAction, zone: kotlinx.datetime.TimeZone): kotlinx.serialization.json.JsonObject? {
    val e = a.eventToAdd() ?: return null
    val start = kotlinx.datetime.Instant.fromEpochMilliseconds(e.startMs)
    val end = e.endMs?.let { kotlinx.datetime.Instant.fromEpochMilliseconds(it) }
    fun midnight(i: kotlinx.datetime.Instant, z: kotlinx.datetime.TimeZone) = i.toLocalDateTime(z).time == kotlinx.datetime.LocalTime(0, 0)
    val dayZone = when {
        midnight(start, zone) && (end == null || midnight(end, zone)) -> zone
        e.bareDate -> kotlinx.datetime.TimeZone.UTC
        else -> null
    }
    val link = kotlinx.serialization.json.JsonObject(mapOf("orrery" to JsonPrimitive(a.id)))
    val draft = if (dayZone != null) {
        val from = start.toLocalDateTime(dayZone).date
        io.nisfeb.talon.calendar.EventDraft(
            name = e.title, location = e.location.orEmpty(), cat = io.nisfeb.talon.calendar.EventCat.ALLDAY, date = from,
            spanDays = end?.let { from.daysUntil(it.toLocalDateTime(dayZone).date) }?.coerceAtLeast(1) ?: 1,
            tags = listOf("orrery"), otherMeta = link,
        )
    } else {
        val local = start.toLocalDateTime(zone)
        val minutes = ((e.endMs ?: (e.startMs + 3_600_000L)) - e.startMs) / 60_000
        io.nisfeb.talon.calendar.EventDraft(
            name = e.title, location = e.location.orEmpty(), cat = io.nisfeb.talon.calendar.EventCat.TIMED, date = local.date,
            minuteOfDay = local.hour * 60 + local.minute, durMin = minutes.toInt().coerceIn(1, 14 * 24 * 60),
            zone = zone.id, tags = listOf("orrery"), otherMeta = link,
        )
    }
    return io.nisfeb.talon.calendar.eventBody(draft)
}
