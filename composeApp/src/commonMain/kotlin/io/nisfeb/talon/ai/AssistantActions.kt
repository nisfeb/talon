package io.nisfeb.talon.ai

import io.nisfeb.talon.calendar.CalendarRepo
import io.nisfeb.talon.calendar.CalendarRow
import io.nisfeb.talon.calendar.daysOf
import io.nisfeb.talon.calendar.draftFromEvent
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import io.nisfeb.talon.calendar.EventCat
import io.nisfeb.talon.calendar.EventDraft
import io.nisfeb.talon.calendar.Repeat
import io.nisfeb.talon.calendar.bounds
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
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

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
    /** Sends a chat message; null where there is no ship session to send with. */
    val send: (suspend (whom: String, text: String) -> Unit)? = null,
)

/**
 * The line under an event's name: a timed event in [zone], a day event
 * as its UTC day, which is how the calendar keeps one.
 */
internal fun eventWhenLine(startMs: Long, endMs: Long, allDay: Boolean, zone: TimeZone): String {
    fun day(d: LocalDate) = "${d.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }} ${d.dayOfMonth} ${MONTHS[d.monthNumber - 1]} ${d.year}"
    if (allDay) {
        val first = Instant.fromEpochMilliseconds(startMs).toLocalDateTime(TimeZone.UTC).date
        val days = ((endMs - startMs) / 86_400_000L).toInt()
        return day(first) + if (days > 1) " · $days days" else " · all day"
    }
    val s = Instant.fromEpochMilliseconds(startMs).toLocalDateTime(zone)
    return "${day(s.date)} · ${io.nisfeb.talon.util.formatTime12(startMs, zone)}–${io.nisfeb.talon.util.formatTime12(endMs, zone)}"
}

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

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
                "Send a signed mail from the user. Recipients are ships (@p); resolve names with find_person first. To answer a thread, give thread and write to its other participants. To mail an invitation, give event: the invite goes along as an .ics file the recipient can add to their calendar.",
                toolSchema(
                    "to" to ("string" to "Recipient ships, comma-separated."),
                    "subject" to ("string" to "The subject line; for a reply, the thread's own subject is used when this is blank."),
                    "body" to ("string" to "The message, plain text."),
                    "thread" to ("string" to "The thread id being answered, from list_mail, search_mail or read_mail; optional."),
                    "event" to ("string" to "An event id from list_events to attach as an invite; optional."),
                    "occurrence" to ("string" to "YYYY-MM-DD of the occurrence to attach, when the event repeats."),
                    required = listOf("to", "body"),
                ),
            ),
            write = true,
        ) { args ->
            val to = args.text("to").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (to.isEmpty()) return@Tool "Error: to is required."
            val bad = to.filterNot { isValidPatp(it) }
            if (bad.isNotEmpty()) return@Tool "Error: not ships: ${bad.joinToString()}. Use find_person to get @p ids."
            val body = args.text("body")?.takeIf { it.isNotBlank() } ?: return@Tool "Error: body is required."
            val threadId = args.text("thread")?.trim()?.takeIf { it.isNotEmpty() }
            val thread = if (threadId == null) null else mail.loadThread(threadId) ?: return@Tool "Error: no thread $threadId; see list_mail."
            val subject = args.text("subject")?.takeIf { it.isNotBlank() }
                ?: thread?.messages?.firstOrNull()?.subject?.let { if (it.startsWith("Re:", ignoreCase = true)) it else "Re: $it" }.orEmpty()
            val attachments = mutableListOf<io.nisfeb.talon.mail.AttachRef>()
            val eventId = args.text("event")?.trim()?.takeIf { it.isNotEmpty() }
            if (eventId != null) {
                val cal = a.calendar ?: return@Tool "Error: there is no calendar to take the event from."
                val row = findOccurrence(cal, eventId, parseDate(args.text("occurrence")), a.zone())
                    ?: return@Tool "Error: no event $eventId coming up; see list_events."
                val ics = io.nisfeb.talon.calendar.eventIcs(row.id, row.name, row.location, row.note, row.l, row.r, row.all)
                val hash = runCatching { mail.uploadBlob(ics.encodeToByteArray()) }
                    .getOrElse { return@Tool "Error: the invite could not be stored: ${it.message}" }
                attachments += io.nisfeb.talon.mail.AttachRef("event.ics", "text/calendar", hash)
            }
            if (mail.send(to, subject, body, thread?.messages?.lastOrNull()?.id, attachments)) {
                "Mailed ${to.joinToString()}${if (attachments.isNotEmpty()) " with the invite attached" else ""}."
            } else {
                "The ship did not send it: ${mail.error.value ?: "unknown reason"}."
            }
        })
        add(Tool(
            spec = ToolSpec(
                "list_mail",
                "The user's mail, newest first: who it is from, the subject, whether it is unread, the thread id and a link that opens it. The inbox unless another view is asked for.",
                toolSchema(
                    "view" to ("string" to "inbox (default), sent, archived or all."),
                    "count" to ("integer" to "How many threads (default 15)."),
                    required = emptyList(),
                ),
            ),
            write = false,
        ) { args ->
            val wanted = (args.text("view") ?: "inbox").trim().lowercase()
            val view = io.nisfeb.talon.mail.MailView.entries.firstOrNull { it.wire == wanted && it != io.nisfeb.talon.mail.MailView.LABEL }
                ?: return@Tool "Error: view must be inbox, sent, archived or all."
            val n = (args.int("count") ?: 15).coerceIn(1, 50)
            val page = mail.listing(view, null, n) ?: return@Tool "The mail app did not answer${mail.error.value?.let { ": $it" } ?: ""}."
            mailLines(page.threads.take(n)).ifBlank { "No mail." }
        })
        add(Tool(
            spec = ToolSpec(
                "search_mail",
                "Search all of the user's mail, sent and archived included, by words in the subject, the body or the sender.",
                toolSchema(
                    "query" to ("string" to "What to look for."),
                    "count" to ("integer" to "How many threads (default 15)."),
                    required = listOf("query"),
                ),
            ),
            write = false,
        ) { args ->
            val q = args.text("query")?.trim()?.takeIf { it.isNotEmpty() } ?: return@Tool "Error: query is required."
            val n = (args.int("count") ?: 15).coerceIn(1, 50)
            val page = mail.listing(io.nisfeb.talon.mail.MailView.ALL, q, n)
                ?: return@Tool "The mail app did not answer${mail.error.value?.let { ": $it" } ?: ""}."
            mailLines(page.threads.take(n)).ifBlank { "No mail matches \"$q\"." }
        })
        add(Tool(
            spec = ToolSpec(
                "read_mail",
                "Read one mail thread: each message with who sent it to whom, when, the subject, the body and its attachments, plus the thread's participants and a link that opens it.",
                toolSchema("thread" to ("string" to "The thread id from list_mail or search_mail."), required = listOf("thread")),
            ),
            write = false,
        ) { args ->
            val id = args.text("thread")?.trim()?.takeIf { it.isNotEmpty() } ?: return@Tool "Error: thread is required."
            val t = mail.loadThread(id) ?: return@Tool "No thread $id${mail.error.value?.let { ": $it" } ?: ""}."
            formatThread(t, a.zone())
        })
    }
    a.calendar?.let { cal ->
        // A calendar named by id or by name, else the one new events go to.
        fun resolveCalendar(arg: String?): String? {
            val q = arg?.trim()?.takeIf { it.isNotBlank() }
            val all = cal.calendars.value
            if (q == null) return cal.defaultCalendar.value.takeIf { d -> all.any { it.id == d } } ?: all.firstOrNull()?.id
            return all.firstOrNull { it.id == q }?.id ?: all.firstOrNull { it.name.equals(q, ignoreCase = true) }?.id
                ?: all.firstOrNull { it.name.contains(q, ignoreCase = true) }?.id
        }
        add(Tool(
            spec = ToolSpec(
                "list_calendars",
                "The user's calendars: id, name and kind (local, google, caldav = followed, ship = shared with the user), and which one new events go to.",
                toolSchema(required = emptyList()),
            ),
            write = false,
        ) { _ ->
            val d = cal.defaultCalendar.value
            cal.calendars.value.joinToString("\n") { c -> "calendar=${c.id} name=${c.name.ifBlank { c.id }} kind=${c.kind}${if (c.id == d) " (default for new events)" else ""}" }.ifBlank { "No calendars." }
        })
        add(Tool(
            spec = ToolSpec(
                "create_event",
                "Put an event on the user's calendar. Dates are YYYY-MM-DD and times HH:MM in the user's zone unless zone is given; work them out from NOW in the system prompt.",
                toolSchema(
                    "name" to ("string" to "What the event is."),
                    "date" to ("string" to "YYYY-MM-DD."),
                    "time" to ("string" to "HH:MM; omit for an all-day event."),
                    "zone" to ("string" to "The zone the time is given in, e.g. Europe/London, when it is not the user's own; optional."),
                    "duration_min" to ("integer" to "Length in minutes (default 60) for a timed event."),
                    "days" to ("integer" to "Length in days (default 1) for an all-day event."),
                    "location" to ("string" to "Where, optional."),
                    "note" to ("string" to "A note, optional."),
                    "calendar" to ("string" to "Calendar id or name (e.g. \"family\"), optional; the calendar new events go to otherwise. See list_calendars."),
                    "repeat" to ("string" to "once (default), daily, weekly, monthly, monthly-nth (a weekday of the month, e.g. the second Tuesday), yearly, or every (every so many minutes)."),
                    "weekdays" to ("string" to "For weekly: comma-separated mon,tue,... (default: the date's weekday)."),
                    "ordinal" to ("string" to "For monthly-nth: first, second, third, fourth or last."),
                    "weekday" to ("string" to "For monthly-nth: mon..sun (default: the date's weekday)."),
                    "every_min" to ("integer" to "For every: the period in minutes."),
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
            val repeat = Repeat.entries.firstOrNull { it.kind == (args.text("repeat") ?: "once").trim().lowercase() }
                ?: return@Tool "Error: repeat must be once, daily, weekly, monthly, monthly-nth, yearly or every."
            if (repeat == Repeat.EVERY && minute == null) return@Tool "Error: an every-so-many-minutes event needs a time."
            val weekdays = args.text("weekdays")?.split(',')?.mapNotNull { WEEKDAYS[it.trim().lowercase().take(3)] }?.toSet()
                ?.takeIf { it.isNotEmpty() } ?: setOf(date.dayOfWeek)
            val ordinal = args.text("ordinal")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            if (ordinal != null && ordinal !in io.nisfeb.talon.calendar.ORDINALS) return@Tool "Error: ordinal must be first, second, third, fourth or last."
            val weekdayText = args.text("weekday")?.trim()?.takeIf { it.isNotEmpty() }
            val nthDay = if (weekdayText == null) null else WEEKDAYS[weekdayText.lowercase().take(3)] ?: return@Tool "Error: weekday must be mon..sun."
            val zoneArg = args.text("zone")?.trim()?.takeIf { it.isNotEmpty() }
            if (zoneArg != null && runCatching { TimeZone.of(zoneArg) }.isFailure) return@Tool "Error: $zoneArg is not a zone name; use one like Europe/London."
            val calArg = args.text("calendar")?.takeIf { it.isNotBlank() }
            val calId = resolveCalendar(calArg)
            if (calArg != null && calId == null) return@Tool "Error: no calendar called \"$calArg\"; see list_calendars."
            if (calId != null && calId in cal.readOnly) return@Tool "Error: calendar $calId is shared with the user read-only; its host makes the changes."
            val draft = EventDraft(
                name = name, note = args.text("note").orEmpty(), location = args.text("location").orEmpty(),
                cal = calId,
                cat = if (minute == null) EventCat.ALLDAY else EventCat.TIMED,
                date = date, minuteOfDay = minute ?: 0,
                durMin = saneDurMin(args.int("duration_min") ?: 60), spanDays = saneSpanDays(args.int("days") ?: 1),
                repeat = repeat, weekdays = weekdays, count = saneCount(args.int("count") ?: 0),
                ordinal = ordinal ?: "first", nthDay = nthDay, periodMin = (args.int("every_min") ?: 60).coerceAtLeast(1),
                zone = if (minute != null) zoneArg else null,
                tags = io.nisfeb.talon.calendar.parseTags(args.text("tags").orEmpty()),
            )
            if (cal.poke(eventBody(draft))) {
                "Added \"$name\" on $date${if (time != null) " at $time${zoneArg?.let { " $it" } ?: ""}" else ""} to calendar ${calId ?: "default"}."
            } else {
                "The calendar did not take it."
            }
        })
        add(Tool(
            spec = ToolSpec(
                "list_events",
                "What is on the user's calendar between two dates, in time order: each event's id, day and time, name, whether it repeats, place, tags, note and calendar. Any range; a week from today by default.",
                toolSchema("from" to ("string" to "YYYY-MM-DD (default today)."), "to" to ("string" to "YYYY-MM-DD inclusive (default a week after from)."), required = emptyList()),
            ),
            write = false,
        ) { args ->
            val zone = a.zone()
            val today = Instant.fromEpochMilliseconds(nowMs()).toLocalDateTime(zone).date
            val from = parseDate(args.text("from")) ?: today
            val to = parseDate(args.text("to")) ?: from.plus(7, DateTimeUnit.DAY)
            if (to < from) return@Tool "Error: to is before from."
            val fromMs = from.atTime(0, 0).toInstant(zone).toEpochMilliseconds()
            val toMs = to.plus(1, DateTimeUnit.DAY).atTime(0, 0).toInstant(zone).toEpochMilliseconds()
            val rows = cal.windowRows(fromMs, toMs) ?: return@Tool "The calendar did not answer."
            val names = cal.calendars.value.associate { it.id to it.name.ifBlank { it.id } }
            val shown = rows.filter { r -> daysOf(r, zone).any { it in from..to } }
            if (shown.isEmpty()) "Nothing between $from and $to."
            else shown.take(100).joinToString("\n") { describeRow(it, zone, names) }
        })
        add(Tool(
            spec = ToolSpec(
                "update_event",
                "Change an event or task already on the calendar: move it, rename it, change its length, place, note, tags or calendar. Give only what changes. For one occurrence of a repeating event give occurrence; without it the whole series changes.",
                toolSchema(
                    "event" to ("string" to "The event or task id from list_events or list_tasks."),
                    "occurrence" to ("string" to "YYYY-MM-DD of the one occurrence to change, for a repeating event."),
                    "name" to ("string" to "A new name."),
                    "date" to ("string" to "A new date, YYYY-MM-DD (a task's due date)."),
                    "time" to ("string" to "A new start time, HH:MM."),
                    "duration_min" to ("integer" to "A new length in minutes."),
                    "days" to ("integer" to "A new length in days, for an all-day event."),
                    "location" to ("string" to "A new place; empty to clear it."),
                    "note" to ("string" to "A new note; empty to clear it."),
                    "calendar" to ("string" to "Move it to this calendar, by id or name."),
                    "tags" to ("string" to "Comma-separated; replaces the tags."),
                    required = listOf("event"),
                ),
            ),
            write = true,
        ) { args ->
            val id = args.text("event")?.trim()?.takeIf { it.isNotEmpty() } ?: return@Tool "Error: event is required."
            val zone = a.zone()
            val today = Instant.fromEpochMilliseconds(nowMs()).toLocalDateTime(zone).date
            val detail = cal.eventDetail(id) ?: return@Tool "Error: no event $id; see list_events."
            val d0 = draftFromEvent(detail, today) ?: return@Tool "Error: that event could not be read."
            if (d0.cal != null && d0.cal in cal.readOnly) return@Tool "Error: that calendar is shared with the user read-only; its host makes the changes."
            val dateText = args.text("date")?.trim()?.takeIf { it.isNotEmpty() }
            val newDate = if (dateText == null) null else parseDate(dateText) ?: return@Tool "Error: date must be YYYY-MM-DD."
            val timeText = args.text("time")?.trim()?.takeIf { it.isNotEmpty() }
            val newMinute = if (timeText == null) null else parseClock(timeText) ?: return@Tool "Error: time must be HH:MM."
            val calArg = args.text("calendar")?.trim()?.takeIf { it.isNotEmpty() }
            val calId = if (calArg == null) d0.cal else resolveCalendar(calArg) ?: return@Tool "Error: no calendar called \"$calArg\"; see list_calendars."
            if (calId != null && calId in cal.readOnly) return@Tool "Error: calendar $calId is shared with the user read-only; its host makes the changes."
            var d = d0.copy(
                name = args.text("name")?.takeIf { it.isNotBlank() } ?: d0.name,
                location = args.text("location") ?: d0.location,
                note = args.text("note") ?: d0.note,
                tags = args.text("tags")?.let { io.nisfeb.talon.calendar.parseTags(it) } ?: d0.tags,
                cal = calId,
                // Only the model's own numbers get clamped: the event's
                // existing span is its business, and a rename must not
                // shorten it.
                durMin = args.int("duration_min")?.let(::saneDurMin) ?: d0.durMin,
                spanDays = args.int("days")?.let(::saneSpanDays) ?: d0.spanDays,
            )
            if (d.cat == EventCat.TODO) {
                if (newDate != null) d = d.copy(due = newDate, date = newDate)
            } else {
                if (newDate != null) d = d.copy(date = newDate)
                if (newMinute != null && d.cat != EventCat.DATE) d = d.copy(cat = EventCat.TIMED, minuteOfDay = newMinute)
            }
            val occText = args.text("occurrence")?.trim()?.takeIf { it.isNotEmpty() }
            val oneOnly = occText != null && d0.repeats
            if (oneOnly) {
                val occ = parseDate(occText) ?: return@Tool "Error: occurrence must be YYYY-MM-DD."
                val row = findOccurrence(cal, id, occ, zone) ?: return@Tool "Error: \"${d0.name}\" has no occurrence on $occ."
                val evZone = d0.zone?.let { z -> runCatching { TimeZone.of(z) }.getOrNull() } ?: zone
                val at = Instant.fromEpochMilliseconds(row.l).toLocalDateTime(if (row.all) TimeZone.UTC else evZone)
                // The calendar page's own two steps, in the safe order: add
                // the one-off FIRST, then skip the original. Skip-first
                // silently LOSES the occurrence when the add then fails;
                // add-first can only leave a visible duplicate, and the
                // partial state is reported honestly below.
                val one = d.copy(
                    repeat = Repeat.ONCE, rawKind = null, rawArgs = null, rawStartMs = null, count = 0, until = null,
                    date = newDate ?: at.date,
                    minuteOfDay = newMinute ?: (at.hour * 60 + at.minute),
                )
                if (!cal.poke(eventBody(one))) return@Tool "The calendar did not take it."
                if (!cal.poke(buildJsonObject { put("action", "skip-event"); put("id", id); put("idx", row.idx) })) {
                    return@Tool "Half done: the changed \"${d.name}\" was added, but the original occurrence on $occ is still there too — the calendar refused the skip. Skip it by hand, or try again."
                }
                return@Tool "Updated \"${d.name}\" for that occurrence."
            }
            if (cal.poke(eventBody(d, id))) "Updated \"${d.name}\"." else "The calendar did not take it."
        })
        add(Tool(
            spec = ToolSpec(
                "delete_event",
                "Remove an event or task from the calendar. For a repeating event give occurrence to skip only that date; without it the whole series is deleted.",
                toolSchema(
                    "event" to ("string" to "The event or task id from list_events or list_tasks."),
                    "occurrence" to ("string" to "YYYY-MM-DD of the one occurrence to skip, for a repeating event."),
                    required = listOf("event"),
                ),
            ),
            write = true,
        ) { args ->
            val id = args.text("event")?.trim()?.takeIf { it.isNotEmpty() } ?: return@Tool "Error: event is required."
            val detail = cal.eventDetail(id) ?: return@Tool "Error: no event $id; see list_events."
            val d = draftFromEvent(detail, Instant.fromEpochMilliseconds(nowMs()).toLocalDateTime(a.zone()).date)
            val name = d?.name?.takeIf { it.isNotBlank() } ?: id
            if (d?.cal != null && d.cal in cal.readOnly) return@Tool "Error: that calendar is shared with the user read-only; its host makes the changes."
            val occText = args.text("occurrence")?.trim()?.takeIf { it.isNotEmpty() }
            if (occText != null && d?.repeats == true) {
                val occ = parseDate(occText) ?: return@Tool "Error: occurrence must be YYYY-MM-DD."
                val row = findOccurrence(cal, id, occ, a.zone()) ?: return@Tool "Error: \"$name\" has no occurrence on $occ."
                return@Tool if (cal.poke(buildJsonObject { put("action", "skip-event"); put("id", id); put("idx", row.idx) })) "Skipped \"$name\" on $occ." else "The calendar did not take it."
            }
            if (cal.poke(buildJsonObject { put("action", "del-event"); put("id", id) })) {
                "Deleted \"$name\"${if (d?.repeats == true) " and every occurrence" else ""}."
            } else {
                "The calendar did not take it."
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
                    "calendar" to ("string" to "Calendar id or name, optional; the calendar new events go to otherwise. See list_calendars."),
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
            val calArg = args.text("calendar")?.takeIf { it.isNotBlank() }
            val calId = resolveCalendar(calArg)
            if (calArg != null && calId == null) return@Tool "Error: no calendar called \"$calArg\"; see list_calendars."
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
                "The user's tasks, soonest due first, undated last: open ones, or done ones too when asked.",
                toolSchema("include_done" to ("boolean" to "true to list done tasks as well."), required = emptyList()),
            ),
            write = false,
        ) { args ->
            val withDone = args.text("include_done") == "true"
            val all = taskOrder(cal.tasks.value.orEmpty().filter { withDone || !it.done })
            // Calendar by NAME, the way list_events shows it — the raw id
            // is opaque to the model and the user alike.
            val names = cal.calendars.value.associate { it.id to it.name.ifBlank { it.id } }
            if (all.isEmpty()) (if (withDone) "No tasks." else "No open tasks.")
            else all.joinToString("\n") { t -> "task=${t.id} ${t.dueDate()?.let { "due $it " } ?: ""}${t.name}${if (t.done) " (done)" else ""} (calendar ${names[t.cal] ?: t.cal})" }
        })
        add(Tool(
            spec = ToolSpec(
                "complete_task",
                "Tick a task off, or reopen a done one. Name it by its id from list_tasks or by (part of) its name.",
                toolSchema(
                    "task" to ("string" to "The task id, or words from its name."),
                    "reopen" to ("boolean" to "true to reopen a done task instead."),
                    required = listOf("task"),
                ),
            ),
            write = true,
        ) { args ->
            val q = args.text("task")?.trim()?.takeIf { it.isNotEmpty() } ?: return@Tool "Error: task is required."
            val reopen = args.text("reopen") == "true"
            val pool = cal.tasks.value.orEmpty().filter { it.done == reopen }
            val hits = pool.filter { it.id == q }.ifEmpty { pool.filter { it.name.contains(q, ignoreCase = true) } }
            when {
                hits.isEmpty() -> "No ${if (reopen) "done" else "open"} task matches \"$q\"."
                hits.size == 1 && hits[0].cal in cal.readOnly -> "That task is on a calendar shared with the user read-only; its host ticks it."
                hits.size > 1 -> "Several match; which one?\n" + hits.joinToString("\n") { "task=${it.id} ${it.name}" }
                else -> if (cal.setDone(hits[0].id, !reopen)) "${if (reopen) "Reopened" else "Done"}: ${hits[0].name}." else "The calendar did not take it."
            }
        })
        add(Tool(
            spec = ToolSpec(
                "calendar_sharing",
                "How the user's calendars are shared and synced: calendars other ships offered the user (with the key to answer them), calendars the user shares out and with whom, calendars shared with the user, when each synced calendar was last pulled and what went wrong, and recent sync refusals.",
                toolSchema(required = emptyList()),
            ),
            write = false,
        ) { _ ->
            val names = cal.calendars.value.associate { it.id to it.name.ifBlank { it.id } }
            val zone = a.zone()
            val s = cal.shares.value
            buildList {
                if (s == null) add("Sharing with ships: this calendar app is too old to have it.")
                s?.offers?.forEach { (key, o) -> add("offer=$key from=${o.host} calendar=${o.name.ifBlank { o.cal }} mode=${o.mode}") }
                s?.shares?.forEach { (id, ships) -> ships.forEach { (ship, mode) -> add("shared_out calendar=${names[id] ?: id} with=$ship mode=$mode") } }
                s?.accepted?.forEach { (id, acc) -> add("shared_with_user calendar=${names[id] ?: id} from=${acc.host} mode=${acc.mode}") }
                cal.calendars.value.filter { it.kind != "local" }.forEach { c ->
                    val row = cal.sync.value[c.id]
                    val last = row?.lastMs?.takeIf { it > 0 }?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(zone).toString() } ?: "never"
                    add("sync calendar=${names[c.id]} kind=${c.kind} last_pull=$last${row?.error?.takeIf { it.isNotBlank() }?.let { " error=$it" } ?: ""}")
                }
                cal.conflicts.value.take(10).forEach { k -> add("refusal calendar=${names[k.cal] ?: k.cal} why=${k.why}") }
            }.joinToString("\n").ifBlank { "Nothing shared, offered or synced." }
        })
        add(Tool(
            spec = ToolSpec(
                "share_calendar",
                "Share one of the user's calendars with another ship, to read or also to edit. They get an offer to accept.",
                toolSchema(
                    "calendar" to ("string" to "Calendar id or name."),
                    "ship" to ("string" to "Their @p, from find_person."),
                    "edit" to ("boolean" to "true to let them edit it too; read-only otherwise."),
                    required = listOf("calendar", "ship"),
                ),
            ),
            write = true,
        ) { args ->
            val calArg = args.text("calendar")?.trim()?.takeIf { it.isNotEmpty() } ?: return@Tool "Error: calendar is required."
            val calId = resolveCalendar(calArg) ?: return@Tool "Error: no calendar called \"$calArg\"; see list_calendars."
            val ship = args.text("ship")?.trim()?.let { if (it.startsWith("~")) it else "~$it" }?.takeIf { isValidPatp(it) }
                ?: return@Tool "Error: ship must be an @p; use find_person."
            val edit = args.text("edit") == "true"
            val calName = cal.calendars.value.firstOrNull { it.id == calId }?.name?.ifBlank { calId } ?: calId
            when (cal.share(calId, ship, edit)) {
                null -> "The calendar would not share \"$calName\"."
                false -> "Shared \"$calName\", but $ship could not be told (down, or no calendar app there yet); share again later to send the offer."
                true -> "Offered \"$calName\" to $ship to read${if (edit) " and edit" else ""}."
            }
        })
        add(Tool(
            spec = ToolSpec(
                "answer_calendar_offer",
                "Accept or decline a calendar another ship offered the user; the key comes from calendar_sharing.",
                toolSchema(
                    "offer" to ("string" to "The offer key from calendar_sharing."),
                    "accept" to ("boolean" to "true to accept, false to decline."),
                    required = listOf("offer", "accept"),
                ),
            ),
            write = true,
        ) { args ->
            val key = args.text("offer")?.trim()?.takeIf { it.isNotEmpty() } ?: return@Tool "Error: offer is required."
            if (cal.shares.value?.offers?.containsKey(key) != true) return@Tool "Error: no offer $key; see calendar_sharing."
            if (args.text("accept") == "true") {
                if (cal.accept(key)) "Accepted; the calendar fills in over the next pull." else "The calendar did not accept it."
            } else {
                if (cal.decline(key)) "Declined." else "The calendar did not decline it."
            }
        })
        add(Tool(
            spec = ToolSpec(
                "sync_calendars",
                "Pull and push every synced calendar now (Google, followed and shared with the user) rather than waiting for the next pass.",
                toolSchema(required = emptyList()),
            ),
            write = false,
        ) { _ ->
            if (cal.syncNow()) "Synced; calendar_sharing shows how each fared." else "A sync did not start; calendar_sharing shows which."
        })
    }
    a.send?.let { send ->
        add(Tool(
            spec = ToolSpec(
                "send_event",
                "Post an event into a chat, DM or channel as an event card everyone who sees it can add to their own calendar. Use this, not send_message, for any event. Name one already on the calendar by its event id from list_events, or give its details.",
                toolSchema(
                    "whom" to ("string" to "Where it goes: a conversation id from find_conversation, or a ship for a DM."),
                    "event" to ("string" to "An event id from list_events, to post one already on the calendar; give date too for one occurrence of a repeating event."),
                    "name" to ("string" to "What the event is, when not posting one from the calendar."),
                    "date" to ("string" to "YYYY-MM-DD."),
                    "time" to ("string" to "HH:MM in the user's zone; omit for an all-day event."),
                    "duration_min" to ("integer" to "Length in minutes (default 60) for a timed event."),
                    "days" to ("integer" to "Length in days (default 1) for an all-day event."),
                    "location" to ("string" to "Where, optional."),
                    "note" to ("string" to "A note, optional."),
                    required = listOf("whom"),
                ),
            ),
            write = true,
        ) { args ->
            val whom = args.text("whom")?.takeIf { it.isNotBlank() } ?: return@Tool "Error: whom is required."
            val zone = a.zone()
            val (ev, err) = eventFrom(args, a, zone)
            if (ev == null) return@Tool err ?: "Error: no event."
            val card = io.nisfeb.talon.calendar.eventCardMessage(
                ev.name, eventWhenLine(ev.l, ev.r, ev.all, zone), ev.location, ev.note, ev.tags, ev.l, ev.r,
            )
            runCatching { send(whom, card) }.fold(
                { "Posted the event to ${a.contacts().conversationLabel(whom)} as a card." },
                { "Error: the message did not go: ${it.message}" },
            )
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

private const val DAY_MS = 86_400_000L

/** read_mail bounds: each body is capped, and so is the message COUNT —
 *  a long thread (a busy mailing list runs to hundreds) would otherwise
 *  blow the tool result past any sane context budget. */
internal const val READ_MAIL_BODY_CHARS = 4000
internal const val READ_MAIL_MAX_MESSAGES = 20

/** One mail thread as the model reads it: header, then the last
 *  [READ_MAIL_MAX_MESSAGES] messages, each body capped. Older messages
 *  are summarized as a count so the model knows they exist. */
internal fun formatThread(t: io.nisfeb.talon.mail.MailThread, zone: TimeZone): String {
    fun two(n: Int) = n.toString().padStart(2, '0')
    val shown = t.messages.takeLast(READ_MAIL_MAX_MESSAGES)
    val earlier = t.messages.size - shown.size
    return buildString {
        append("thread=${t.id} link=${io.nisfeb.talon.urbit.TalonLink.forMail(t.id)} participants=${t.participants.joinToString(", ")}")
        if (t.labels.isNotEmpty()) append(" labels=${t.labels.joinToString(", ")}")
        if (earlier > 0) append("\n\n… and $earlier earlier message${if (earlier == 1) "" else "s"} in this thread, not shown; these are the ${shown.size} most recent.")
        shown.forEach { m ->
            val at = Instant.fromEpochMilliseconds(m.sent).toLocalDateTime(zone)
            append("\n\nmessage=${m.id} from=${m.from} to=${m.to.joinToString(", ")} sent=${at.date} ${two(at.hour)}:${two(at.minute)} subject=${m.subject.ifBlank { "(no subject)" }}\n")
            append(m.body.take(READ_MAIL_BODY_CHARS))
            if (m.attachments.isNotEmpty()) append("\nattachments: " + m.attachments.joinToString(", ") { "${it.name} (${it.mime})" })
        }
    }
}

/** Tool-arg hygiene for the calendar writes: the model can hand us an
 *  absurd duration_min / days / count (a hallucinated 3_000_000). Coerce
 *  at the tool boundary, the way eventFrom does, with upper caps matched
 *  to what the calendar can sanely hold. */
internal fun saneDurMin(v: Int): Int = v.coerceIn(0, 7 * 24 * 60) // up to a week
internal fun saneSpanDays(v: Int): Int = v.coerceIn(1, 62) // daysOf's own display cap
internal fun saneCount(v: Int): Int = v.coerceIn(0, 1000)

private fun mailLines(threads: List<io.nisfeb.talon.mail.InboxEntry>): String = threads.joinToString("\n") {
    "thread=${it.id} link=${io.nisfeb.talon.urbit.TalonLink.forMail(it.id)} from=${it.from} participants=${it.participants.joinToString(",")} subject=${it.subject.ifBlank { "(no subject)" }}${if (it.unread) " unread" else ""}"
}

/** One calendar row as the model reads it: id, when, what, repeats, where, tags, note, calendar by name. */
internal fun describeRow(r: CalendarRow, zone: TimeZone, calNames: Map<String, String>): String {
    fun hm(ms: Long) = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone).let { "${it.hour.toString().padStart(2, '0')}:${it.minute.toString().padStart(2, '0')}" }
    val days = daysOf(r, zone)
    val whenPart = when {
        r.isTask -> "${days.first()} due${if (r.done) " done" else ""}"
        r.all -> "${days.first()} all day${if (days.size > 1) " (${days.size} days)" else ""}"
        else -> "${days.first()} ${hm(r.l)}–${hm(r.r)}"
    }
    return buildString {
        append(if (r.isTask) "task=" else "event=").append(r.id).append(' ').append(whenPart).append(' ').append(r.name.ifBlank { "(untitled)" })
        if (r.repeats) append(" (repeats ").append(r.kind).append(')')
        if (r.location.isNotBlank()) append(" @ ").append(r.location)
        if (r.tags.isNotEmpty()) append(' ').append(r.tags.joinToString(" ") { "#$it" })
        if (r.note.isNotBlank()) append(" note: ").append(r.note.replace('\n', ' ').take(160))
        append(" (calendar ").append(calNames[r.cal] ?: r.cal).append(')')
    }
}

/** The occurrence of [id] on [on], or else its next one, reading past the widget's window when need be. */
private suspend fun findOccurrence(cal: CalendarRepo, id: String, on: LocalDate?, zone: TimeZone): CalendarRow? {
    fun fits(r: CalendarRow) = r.id == id && (on == null || on in daysOf(r, zone))
    val now = nowMs()
    cal.rows.value.orEmpty().firstOrNull { fits(it) && (on != null || it.r > now) }?.let { return it }
    val span = if (on != null) {
        val d = on.atTime(0, 0).toInstant(zone).toEpochMilliseconds()
        (d - DAY_MS) to (d + 2 * DAY_MS)
    } else {
        (now - 180 * DAY_MS) to (now + 365 * DAY_MS)
    }
    val rows = cal.windowRows(span.first, span.second).orEmpty().filter { fits(it) }
    return rows.firstOrNull { on != null || it.r > now } ?: rows.lastOrNull()
}

/** The event a tool was pointed at: from the calendar by id, or from its details. Else the error to answer with. */
private suspend fun eventFrom(args: JsonObject, a: AssistantActions, zone: TimeZone): Pair<CalendarRow?, String?> {
    val eventId = args.text("event")?.trim()?.takeIf { it.isNotEmpty() }
    if (eventId != null) {
        val cal = a.calendar ?: return null to "Error: there is no calendar to take the event from."
        val on = parseDate(args.text("date"))
        val row = findOccurrence(cal, eventId, on, zone) ?: return null to "Error: no event $eventId${on?.let { " on $it" } ?: " coming up"}; see list_events."
        return row to null
    }
    val name = args.text("name")?.takeIf { it.isNotBlank() } ?: return null to "Error: give an event id or a name."
    val date = parseDate(args.text("date")) ?: return null to "Error: date must be YYYY-MM-DD."
    val time = args.text("time")?.takeIf { it.isNotBlank() }
    val start: Long
    val end: Long
    if (time == null) {
        start = date.atTime(0, 0).toInstant(TimeZone.UTC).toEpochMilliseconds()
        end = start + saneSpanDays(args.int("days") ?: 1) * DAY_MS
    } else {
        val minute = parseClock(time) ?: return null to "Error: time must be HH:MM."
        start = date.atTime(minute / 60, minute % 60).toInstant(zone).toEpochMilliseconds()
        end = start + saneDurMin(args.int("duration_min") ?: 60) * 60_000L
    }
    val meta = buildJsonObject {
        put("name", name)
        args.text("location")?.takeIf { it.isNotBlank() }?.let { put("location", it) }
        args.text("note")?.takeIf { it.isNotBlank() }?.let { put("note", it) }
    }
    return CalendarRow(id = "", meta = meta, cat = if (time == null) "allday" else "timed", all = time == null, l = start, r = end) to null
}
