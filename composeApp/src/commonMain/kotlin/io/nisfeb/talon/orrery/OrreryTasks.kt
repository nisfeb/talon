package io.nisfeb.talon.orrery

import io.nisfeb.talon.calendar.CalendarTask
import io.nisfeb.talon.calendar.metaStr
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
}

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
private val REFUSED = setOf("dismissed", "failed")

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
    for (a in tasks) {
        val todo = byAction[a.id]
        when {
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
