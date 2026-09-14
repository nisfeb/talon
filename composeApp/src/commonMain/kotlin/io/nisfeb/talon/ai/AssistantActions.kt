package io.nisfeb.talon.ai

import io.nisfeb.talon.calendar.CalendarRepo
import io.nisfeb.talon.calendar.EventCat
import io.nisfeb.talon.calendar.EventDraft
import io.nisfeb.talon.calendar.Repeat
import io.nisfeb.talon.calendar.eventBody
import io.nisfeb.talon.calendar.dueDate
import io.nisfeb.talon.calendar.taskOrder
import io.nisfeb.talon.call.CallController
import io.nisfeb.talon.call.PartyLineHost
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.ContactEntity
import io.nisfeb.talon.mail.MailRepo
import io.nisfeb.talon.ui.ContactMap
import io.nisfeb.talon.ui.NameToShip
import io.nisfeb.talon.ui.shipHandle
import io.nisfeb.talon.ui.shipHandleLong
import io.nisfeb.talon.urbit.isValidPatp
import io.nisfeb.talon.util.nowMs
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * What the assistant can act on beyond chat: people, conversations,
 * mail, the calendar, calls and party lines. Each is optional; a host
 * that has no mail or no calls simply hands the agent fewer tools.
 */
class AssistantActions(
    val db: AppDatabase,
    val contacts: () -> ContactMap,
    val mail: MailRepo? = null,
    val calendar: CalendarRepo? = null,
    val calls: CallController? = null,
    val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
)

/** A person the assistant can address: the ship and the names it goes by. */
data class PersonMatch(val ship: String, val names: List<String>)

/**
 * Who [query] means, by @p, nickname, word name or any of them in part.
 * Exact matches first, then the rest, so "sunbum" finds ~sunbum-... and
 * a nickname "Sun" does not crowd it out.
 */
fun findPeople(query: String, contacts: List<ContactEntity>): List<PersonMatch> {
    val q = query.trim().lowercase().removePrefix("@")
    if (q.isEmpty()) return emptyList()
    val known = contacts.map { it.ship }
    val exact = NameToShip.resolve(q, known) { ship -> contacts.firstOrNull { it.ship == ship }?.nickname }
    // A bare word that happens to be a galaxy's name ("sun") is a
    // contact lookup, not the galaxy: an @p counts only typed with its
    // sigil or already in the book.
    val exactShip = (exact as? NameToShip.Result.One)?.ship?.takeIf { q.startsWith("~") || it in known }
    fun namesOf(c: ContactEntity) = listOfNotNull(c.nickname?.takeIf { it.isNotBlank() }, shipHandle(c.ship).takeIf { it != c.ship }, shipHandleLong(c.ship), c.ship)
    val partial = contacts.filter { c ->
        c.ship != exactShip && namesOf(c).any { it.lowercase().trimStart('~', '.').contains(q.trimStart('~', '.')) }
    }
    val head = listOfNotNull(exactShip?.let { s -> contacts.firstOrNull { it.ship == s }?.let { PersonMatch(s, namesOf(it)) } ?: PersonMatch(s, listOf(s)) })
    return head + partial.map { PersonMatch(it.ship, namesOf(it)) }
}

/** "2026-09-19" -> a date, or null. */
fun parseDate(s: String?): LocalDate? = s?.trim()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

/** "12:30" -> minutes since midnight, or null. */
fun parseClock(s: String?): Int? {
    val m = Regex("""^(\d{1,2}):(\d{2})$""").find(s?.trim().orEmpty()) ?: return null
    val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
    return if (h in 0..23 && mi in 0..59) h * 60 + mi else null
}

/** The line the model reads first: what time it is where the user is. */
fun nowLine(zone: TimeZone, atMs: Long = nowMs()): String {
    val t = Instant.fromEpochMilliseconds(atMs).toLocalDateTime(zone)
    return "NOW: ${t.date} (${t.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }}) ${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}, zone ${zone.id}. " +
        "Resolve relative dates (\"Saturday\", \"tomorrow\", \"next week\") from this; a weekday named without a date means the next one to come."
}

private val WEEKDAYS = mapOf("mon" to DayOfWeek.MONDAY, "tue" to DayOfWeek.TUESDAY, "wed" to DayOfWeek.WEDNESDAY, "thu" to DayOfWeek.THURSDAY, "fri" to DayOfWeek.FRIDAY, "sat" to DayOfWeek.SATURDAY, "sun" to DayOfWeek.SUNDAY)

/** The tools for [a]. Writes are confirmed by the user before they run. */
fun actionTools(a: AssistantActions): List<Tool> = buildList {
    add(Tool(
        spec = ToolSpec(
            "find_person",
            "Find a person the user knows by name, nickname, word name or @p. Returns ships with the names they go by; a ship's own id is also the whom of a direct message to them. Use before mailing, messaging or calling someone named in the request.",
            toolSchema("name" to ("string" to "The name as the user said it."), required = listOf("name")),
        ),
        write = false,
    ) { args ->
        val name = args.text("name") ?: return@Tool "Error: name is required."
        val found = findPeople(name, a.contacts().contacts)
        if (found.isEmpty()) "Nobody matches \"$name\"."
        else found.take(8).joinToString("\n") { "ship=${it.ship} dm_whom=${it.ship} names=${it.names.joinToString(", ")}" }
    })
    add(Tool(
        spec = ToolSpec(
            "find_conversation",
            "Find a group, a group's channel, or a group chat by name. Returns whom ids to send to: a channel's whom is chat/~host/name, a club's is its id. For a person, use find_person instead.",
            toolSchema("name" to ("string" to "The group, channel or chat name."), required = listOf("name")),
        ),
        write = false,
    ) { args ->
        val q = args.text("name")?.lowercase()?.trim() ?: return@Tool "Error: name is required."
        val groups = a.db.groups().allGroups()
        val channels = a.db.groups().allChannelGroups()
        val cm = a.contacts()
        val lines = buildList {
            groups.filter { (it.title ?: it.flag).lowercase().contains(q) }.forEach { g ->
                val chans = channels.filter { it.groupFlag == g.flag }
                add("group=${g.flag} title=${g.title ?: g.flag} channels=" + chans.joinToString(", ") { "${it.nest} (${it.title ?: it.nest.substringAfterLast('/')})" })
            }
            channels.filter { (it.title ?: "").lowercase().contains(q) || it.nest.substringAfterLast('/').lowercase().contains(q) }
                .forEach { add("whom=${it.nest} title=${it.title ?: it.nest} group=${it.groupFlag}") }
            cm.clubs.filter { (it.title ?: "").lowercase().contains(q) }.forEach { add("whom=${it.id} title=${it.title} (group chat)") }
        }
        if (lines.isEmpty()) "Nothing matches \"$q\"." else lines.take(12).joinToString("\n")
    })
    a.mail?.let { mail ->
        add(Tool(
            spec = ToolSpec(
                "send_mail",
                "Send a signed mail from the user. Recipients are ships (@p); resolve names with find_person first.",
                toolSchema(
                    "to" to ("string" to "Recipient ships, comma-separated."),
                    "subject" to ("string" to "The subject line."),
                    "body" to ("string" to "The message, plain text."),
                    required = listOf("to", "subject", "body"),
                ),
            ),
            write = true,
        ) { args ->
            val to = args.text("to").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (to.isEmpty()) return@Tool "Error: to is required."
            val bad = to.filterNot { isValidPatp(it) }
            if (bad.isNotEmpty()) return@Tool "Error: not ships: ${bad.joinToString()}. Use find_person to get @p ids."
            val subject = args.text("subject").orEmpty()
            val body = args.text("body")?.takeIf { it.isNotBlank() } ?: return@Tool "Error: body is required."
            if (mail.send(to, subject, body, null, emptyList())) "Mailed ${to.joinToString()}." else "The ship did not send it: ${mail.error.value ?: "unknown reason"}."
        })
        add(Tool(
            spec = ToolSpec(
                "list_mail",
                "The user's inbox, newest first: who it is from, the subject, whether it is unread.",
                toolSchema("count" to ("integer" to "How many threads (default 15)."), required = emptyList()),
            ),
            write = false,
        ) { args ->
            val n = (args.int("count") ?: 15).coerceIn(1, 50)
            val threads = mail.page.value?.threads.orEmpty().take(n)
            if (threads.isEmpty()) "No mail." else threads.joinToString("\n") { "thread=${it.id} from=${it.from} subject=${it.subject.ifBlank { "(no subject)" }}${if (it.unread) " unread" else ""}" }
        })
    }
    a.calendar?.let { cal ->
        add(Tool(
            spec = ToolSpec(
                "create_event",
                "Put an event on the user's calendar. Dates are YYYY-MM-DD, times HH:MM in the user's zone; work them out from NOW in the system prompt.",
                toolSchema(
                    "name" to ("string" to "What the event is."),
                    "date" to ("string" to "YYYY-MM-DD."),
                    "time" to ("string" to "HH:MM; omit for an all-day event."),
                    "duration_min" to ("integer" to "Length in minutes (default 60) for a timed event."),
                    "days" to ("integer" to "Length in days (default 1) for an all-day event."),
                    "location" to ("string" to "Where, optional."),
                    "note" to ("string" to "A note, optional."),
                    "calendar" to ("string" to "Calendar id, optional; the default calendar otherwise."),
                    "repeat" to ("string" to "once (default), daily, weekly, monthly or yearly."),
                    "weekdays" to ("string" to "For weekly: comma-separated mon,tue,... (default: the date's weekday)."),
                    "count" to ("integer" to "For a repeat: how many times, optional."),
                    "tags" to ("string" to "Comma-separated tags, optional (the calendar's categories)."),
                    required = listOf("name", "date"),
                ),
            ),
            write = true,
        ) { args ->
            val name = args.text("name")?.takeIf { it.isNotBlank() } ?: return@Tool "Error: name is required."
            val date = parseDate(args.text("date")) ?: return@Tool "Error: date must be YYYY-MM-DD."
            val time = args.text("time")?.takeIf { it.isNotBlank() }
            val minute = if (time == null) null else parseClock(time) ?: return@Tool "Error: time must be HH:MM."
            val repeat = Repeat.entries.firstOrNull { it.kind == (args.text("repeat") ?: "once").lowercase() } ?: return@Tool "Error: repeat must be once, daily, weekly, monthly or yearly."
            val weekdays = args.text("weekdays")?.split(',')?.mapNotNull { WEEKDAYS[it.trim().lowercase().take(3)] }?.toSet()
                ?.takeIf { it.isNotEmpty() } ?: setOf(date.dayOfWeek)
            val calId = args.text("calendar")?.takeIf { it.isNotBlank() }
            if (calId != null && calId in cal.readOnly) return@Tool "Error: calendar $calId is shared with the user read-only; its host makes the changes."
            val draft = EventDraft(
                name = name, note = args.text("note").orEmpty(), location = args.text("location").orEmpty(),
                cal = calId,
                cat = if (minute == null) EventCat.ALLDAY else EventCat.TIMED,
                date = date, minuteOfDay = minute ?: 0,
                durMin = args.int("duration_min") ?: 60, spanDays = args.int("days") ?: 1,
                repeat = repeat, weekdays = weekdays, count = args.int("count") ?: 0,
                tags = io.nisfeb.talon.calendar.parseTags(args.text("tags").orEmpty()),
            )
            if (cal.poke(eventBody(draft))) "Added \"$name\" on $date${if (time != null) " at $time" else ""}." else "The calendar did not take it."
        })
        add(Tool(
            spec = ToolSpec(
                "list_events",
                "What is on the user's calendar between two dates (within the next thirty days), in time order.",
                toolSchema("from" to ("string" to "YYYY-MM-DD (default today)."), "to" to ("string" to "YYYY-MM-DD inclusive (default a week out)."), required = emptyList()),
            ),
            write = false,
        ) { args ->
            val zone = a.zone()
            val today = Instant.fromEpochMilliseconds(nowMs()).toLocalDateTime(zone).date
            val from = parseDate(args.text("from")) ?: today
            val to = parseDate(args.text("to")) ?: LocalDate.fromEpochDays(from.toEpochDays() + 7)
            val rows = cal.rows.value.orEmpty().filter { r ->
                val d = Instant.fromEpochMilliseconds(r.l).toLocalDateTime(zone).date
                d >= from && d <= to
            }
            if (rows.isEmpty()) "Nothing between $from and $to."
            else rows.joinToString("\n") { r ->
                val s = Instant.fromEpochMilliseconds(r.l).toLocalDateTime(zone)
                "${if (r.isTask) "task" else "event"}=${r.id} ${s.date} ${if (r.isTask) (if (r.done) "done" else "due") else if (r.all) "all day" else "${s.hour.toString().padStart(2, '0')}:${s.minute.toString().padStart(2, '0')}"} ${r.name}${if (r.location.isNotBlank()) " @ ${r.location}" else ""} (calendar ${r.cal})"
            }
        })
        add(Tool(
            spec = ToolSpec(
                "create_task",
                "Put a task (a to-do) on the user's calendar, with a due date if one was given.",
                toolSchema(
                    "name" to ("string" to "What is to be done."),
                    "due" to ("string" to "YYYY-MM-DD, optional; omit for no due date."),
                    "note" to ("string" to "A note, optional."),
                    "calendar" to ("string" to "Calendar id, optional; the default calendar otherwise."),
                    "tags" to ("string" to "Comma-separated tags, optional."),
                    required = listOf("name"),
                ),
            ),
            write = true,
        ) { args ->
            val name = args.text("name")?.takeIf { it.isNotBlank() } ?: return@Tool "Error: name is required."
            val dueText = args.text("due")?.takeIf { it.isNotBlank() }
            val due = if (dueText == null) null else parseDate(dueText) ?: return@Tool "Error: due must be YYYY-MM-DD."
            val today = Instant.fromEpochMilliseconds(nowMs()).toLocalDateTime(a.zone()).date
            val calId = args.text("calendar")?.takeIf { it.isNotBlank() }
            if (calId != null && calId in cal.readOnly) return@Tool "Error: calendar $calId is shared with the user read-only; its host makes the changes."
            val draft = EventDraft(
                name = name, note = args.text("note").orEmpty(), cal = calId,
                cat = EventCat.TODO, date = due ?: today, due = due,
                tags = io.nisfeb.talon.calendar.parseTags(args.text("tags").orEmpty()),
            )
            if (cal.poke(eventBody(draft))) "Added task \"$name\"${if (due != null) " due $due" else ""}." else "The calendar did not take it."
        })
        add(Tool(
            spec = ToolSpec(
                "list_tasks",
                "The user's open tasks, soonest due first, undated last.",
                toolSchema(required = emptyList()),
            ),
            write = false,
        ) { _ ->
            val open = taskOrder(cal.tasks.value.orEmpty().filter { !it.done })
            if (open.isEmpty()) "No open tasks." else open.joinToString("\n") { t -> "task=${t.id} ${t.dueDate()?.let { "due $it " } ?: ""}${t.name} (calendar ${t.cal})" }
        })
        add(Tool(
            spec = ToolSpec(
                "complete_task",
                "Tick a task off. Name it by its id from list_tasks or by (part of) its name.",
                toolSchema("task" to ("string" to "The task id, or words from its name."), required = listOf("task")),
            ),
            write = true,
        ) { args ->
            val q = args.text("task")?.trim()?.takeIf { it.isNotEmpty() } ?: return@Tool "Error: task is required."
            val open = cal.tasks.value.orEmpty().filter { !it.done }
            val hits = open.filter { it.id == q }.ifEmpty { open.filter { it.name.contains(q, ignoreCase = true) } }
            when {
                hits.isEmpty() -> "No open task matches \"$q\"."
                hits.size == 1 && hits[0].cal in cal.readOnly -> "That task is on a calendar shared with the user read-only; its host ticks it."
                hits.size > 1 -> "Several match; which one?\n" + hits.joinToString("\n") { "task=${it.id} ${it.name}" }
                else -> if (cal.setDone(hits[0].id, true)) "Done: ${hits[0].name}." else "The calendar did not take it."
            }
        })
    }
    a.calls?.let { calls ->
        add(Tool(
            spec = ToolSpec(
                "join_party_line",
                "Join the party line (group voice call) of a group channel. whom is the channel's id from find_conversation.",
                toolSchema("whom" to ("string" to "The channel whom, e.g. chat/~host/name."), required = listOf("whom")),
            ),
            write = true,
        ) { args ->
            val whom = args.text("whom") ?: return@Tool "Error: whom is required."
            if (PartyLineHost.joinLine(calls, a.db, whom)) "Joining the line." else "That channel has no party line."
        })
        add(Tool(
            spec = ToolSpec(
                "call_person",
                "Start a voice call with one person.",
                toolSchema("ship" to ("string" to "Their @p, from find_person."), required = listOf("ship")),
            ),
            write = true,
        ) { args ->
            val ship = args.text("ship")?.takeIf { isValidPatp(it) } ?: return@Tool "Error: ship must be an @p; use find_person."
            calls.placeCall(ship); "Calling $ship."
        })
        add(Tool(
            spec = ToolSpec("hang_up", "Leave the current call or party line.", toolSchema(required = emptyList())),
            write = true,
        ) { calls.hangup(); "Hung up." })
    }
}

private fun JsonObject.text(key: String): String? = this[key]?.let { (it as? JsonPrimitive)?.contentOrNull }
private fun JsonObject.int(key: String): Int? = this[key]?.let { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }

internal fun toolSchema(vararg props: Pair<String, Pair<String, String>>, required: List<String>): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
        props.forEach { (name, td) -> put(name, buildJsonObject { put("type", td.first); put("description", td.second) }) }
    })
    putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
}
