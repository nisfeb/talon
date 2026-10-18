package io.nisfeb.talon.ai

import io.nisfeb.talon.calendar.CalendarRow
import io.nisfeb.talon.calendar.CalendarTask
import io.nisfeb.talon.calendar.daysOf
import io.nisfeb.talon.calendar.dueDate
import kotlinx.datetime.DateTimeUnit
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/**
 * What a dictation's answer card shows besides its line: the days and the
 * tasks the answer read, as a calendar shows them, not as the sentence a
 * model made of them. sneagan, 2026-10-16: "the calendar answer ... was
 * just a series of event names rather than a proper calendar event list card".
 */
data class DictationCard(
    val days: List<CardDay> = emptyList(),
    val tasks: List<CardTask> = emptyList(),
    val people: List<CardPerson> = emptyList(),
    val messages: List<CardMessage> = emptyList(),
    val mail: List<CardMail> = emptyList(),
) {
    val isEmpty: Boolean get() = days.isEmpty() && tasks.isEmpty() && people.isEmpty() && messages.isEmpty() && mail.isEmpty()

    /** Each person's names, to the ship a tap on one in the answer opens. */
    val names: Map<String, String> get() = people.flatMap { p -> p.names.map { it to p.ship } }.toMap()
}

/** Someone the answer looked up: their ship, the name they go by, and every name a tap on them is found by. */
data class CardPerson(val ship: String, val name: String, val names: List<String>)

/** A chat message the answer read, and the talon:// address that opens it. */
data class CardMessage(val author: String, val text: String, val where: String, val time: String, val link: String)

/** A mail thread the answer read, and the talon:// address that opens it. */
data class CardMail(val from: String, val subject: String, val snippet: String, val time: String, val unread: Boolean, val link: String)

/** What orrery left waiting, as the card shows it: who a message goes to and what it says, when an event is, when a task is due. */
data class CardProposal(val kind: String, val title: String, val to: String? = null, val text: String? = null, val time: String? = null, val place: String? = null)

/** One day: "Today", "Tomorrow", "Saturday, Oct 18". */
data class CardDay(val label: String, val events: List<CardEvent>)

/** One event: its time ("4:45 PM – 7:15 PM", "All day"), name, place, colour, and whether it is on now or over. */
data class CardEvent(val time: String, val name: String, val place: String, val color: String?, val now: Boolean, val over: Boolean)

/** An open task, with when it is due ("Today", "Oct 20") and whether that has passed. */
data class CardTask(val name: String, val due: String?, val overdue: Boolean)

/**
 * What the tools showed during one run, for its card: fed by
 * [AssistantActions]' onEvents, onTasks, onPeople and onMail and by
 * [ToolCatalog]'s onMessages. A dictation's and the Assistant's both.
 */
class CardSeen {
    private var events: Triple<List<CalendarRow>, LocalDate, LocalDate>? = null
    private var tasks: List<CalendarTask>? = null
    private val people = mutableListOf<PersonMatch>()
    private var messages: List<io.nisfeb.talon.data.MessageEntity>? = null
    private var mail: List<io.nisfeb.talon.mail.InboxEntry>? = null
    fun people(found: List<PersonMatch>) { people += found }
    fun messages(m: List<io.nisfeb.talon.data.MessageEntity>) { messages = m }
    fun mail(m: List<io.nisfeb.talon.mail.InboxEntry>) { mail = m }
    fun events(rows: List<CalendarRow>, from: LocalDate, to: LocalDate) { events = Triple(rows, from, to) }
    fun tasks(t: List<CalendarTask>) { tasks = t }

    /** Forget what was seen, for the next run or the next of a conversation's turns. */
    fun reset() { events = null; tasks = null; people.clear(); messages = null; mail = null }

    /** The card of what was seen, each ship by [name], times on a 24-hour clock where [twentyFour]. */
    fun card(zone: TimeZone, name: (String) -> String, twentyFour: Boolean, nowMs: Long = io.nisfeb.talon.util.nowMs()): DictationCard = DictationCard(
        days = events?.let { (r, f, t) -> DictationCards.days(r, f, t, zone, nowMs, twentyFour) }.orEmpty(),
        tasks = tasks?.let { DictationCards.tasks(it, zone, nowMs) }.orEmpty(),
        people = DictationCards.people(people),
        messages = messages?.let { m ->
            DictationCards.messages(m, name, { io.nisfeb.talon.urbit.StoryCache.textFor(it.id, it.contentJson) }, zone, nowMs, twentyFour)
        }.orEmpty(),
        mail = mail?.let { DictationCards.mail(it, name, zone, nowMs, twentyFour) }.orEmpty(),
    )
}

object DictationCards {
    /** The events of [rows] on each day from [from] to [to], all-day first then by start; days with none left out. */
    fun days(rows: List<CalendarRow>, from: LocalDate, to: LocalDate, zone: TimeZone, nowMs: Long, twentyFour: Boolean): List<CardDay> {
        val today = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone).date
        val events = rows.filterNot { it.isTask }
        return generateSequence(from) { d -> d.plus(1, DateTimeUnit.DAY).takeIf { it <= to } }
            .take(MAX_DAYS)
            .mapNotNull { day ->
                val on = events.filter { day in daysOf(it, zone) }
                    .sortedWith(compareBy({ !it.all }, { it.l }, { it.name }))
                if (on.isEmpty()) return@mapNotNull null
                CardDay(dayLabel(day, today), on.map { r ->
                    CardEvent(
                        time = if (r.all) "All day" else "${clock(r.l, zone, twentyFour)} – ${clock(r.r, zone, twentyFour)}",
                        name = r.name.ifBlank { "(untitled)" },
                        place = r.location,
                        color = r.color,
                        now = !r.all && r.l <= nowMs && nowMs < r.r,
                        over = r.r <= nowMs,
                    )
                })
            }.toList()
    }

    /** The open ones of [tasks], soonest due first and undated last. */
    fun tasks(tasks: List<CalendarTask>, zone: TimeZone, nowMs: Long): List<CardTask> {
        val today = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone).date
        return tasks.filterNot { it.done }
            .sortedWith(compareBy(nullsLast()) { it.dueDate() })
            .take(MAX_TASKS)
            .map { t ->
                val due = t.dueDate()
                CardTask(t.name.ifBlank { "(untitled)" }, due?.let { dayLabel(it, today, short = true) }, due != null && due < today)
            }
    }

    fun dayLabel(d: LocalDate, today: LocalDate, short: Boolean = false): String = when (d) {
        today -> "Today"
        today.plus(1, DateTimeUnit.DAY) -> "Tomorrow"
        else -> {
            val month = d.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
            val date = "$month ${d.day}"
            if (short) date else d.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() } + ", " + date
        }
    }

    fun clock(ms: Long, zone: TimeZone, twentyFour: Boolean): String {
        val t = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone)
        val mm = t.minute.toString().padStart(2, '0')
        if (twentyFour) return "${t.hour.toString().padStart(2, '0')}:$mm"
        val h = (t.hour % 12).let { if (it == 0) 12 else it }
        return "$h:$mm ${if (t.hour < 12) "AM" else "PM"}"
    }

    /** The people the answer found, once each, by the name they go by. */
    fun people(found: List<PersonMatch>): List<CardPerson> = found.distinctBy { it.ship }.take(MAX_ROWS).map { m ->
        CardPerson(m.ship, m.names.firstOrNull { !it.startsWith("~") } ?: m.ship, m.names.filter { it.length >= 3 })
    }

    /** The messages the answer read, newest first. */
    fun messages(
        rows: List<io.nisfeb.talon.data.MessageEntity>,
        name: (String) -> String,
        text: (io.nisfeb.talon.data.MessageEntity) -> String,
        zone: TimeZone,
        nowMs: Long,
        twentyFour: Boolean,
    ): List<CardMessage> = rows.filterNot { it.isDeleted }.sortedByDescending { it.sentMs }.take(MAX_ROWS).map { m ->
        CardMessage(
            author = name(m.author),
            text = text(m).replace('\n', ' ').trim().take(240),
            where = if (m.whom.startsWith("~")) "Chat with ${name(m.whom)}" else m.whom.substringAfterLast('/'),
            time = whenLabel(m.sentMs, zone, nowMs, twentyFour),
            link = io.nisfeb.talon.urbit.TalonLink.forMessage(m.whom, m.id, m.parentId),
        )
    }

    /** The mail threads the answer read. */
    fun mail(threads: List<io.nisfeb.talon.mail.InboxEntry>, name: (String) -> String, zone: TimeZone, nowMs: Long, twentyFour: Boolean): List<CardMail> =
        threads.take(MAX_ROWS).map { t ->
            CardMail(
                from = name(t.from),
                subject = t.subject.ifBlank { "(no subject)" },
                snippet = t.snippet.replace('\n', ' ').trim().take(160),
                time = if (t.last > 0) whenLabel(t.last, zone, nowMs, twentyFour) else "",
                unread = t.unread,
                link = io.nisfeb.talon.urbit.TalonLink.forMail(t.id),
            )
        }

    /** A moment as a list says it: the time today, "Yesterday", or the date. */
    fun whenLabel(ms: Long, zone: TimeZone, nowMs: Long, twentyFour: Boolean): String {
        val today = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone).date
        val d = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone).date
        return when (d) {
            today -> clock(ms, zone, twentyFour)
            today.plus(-1, DateTimeUnit.DAY) -> "Yesterday"
            else -> dayLabel(d, today, short = true)
        }
    }

    /**
     * What orrery left waiting, as the card shows it. A message says who
     * it goes to by name ("person/me" is "You"), an event when and where,
     * a task when it is due.
     */
    fun proposal(a: io.nisfeb.talon.orrery.OrreryAction, zone: TimeZone, nowMs: Long, twentyFour: Boolean): CardProposal {
        fun str(k: String) = (a.payload[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
        fun at(k: String) = str(k)?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }
        val today = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone).date
        return when (a.kind) {
            "message" -> CardProposal(a.kind, a.title, to = str("to")?.let(::bodyName), text = str("text"))
            "calendar" -> {
                val starts = at("starts")
                val ends = at("ends")
                val time = starts?.let { s ->
                    val day = dayLabel(Instant.fromEpochMilliseconds(s).toLocalDateTime(zone).date, today)
                    day + " · " + clock(s, zone, twentyFour) + (ends?.let { " – " + clock(it, zone, twentyFour) } ?: "")
                }
                CardProposal(a.kind, str("title") ?: a.title, time = time, place = str("location"))
            }
            "task" -> CardProposal(
                a.kind, a.title,
                time = a.due?.let { d -> runCatching { Instant.parse(d).toLocalDateTime(zone).date }.getOrNull() ?: runCatching { LocalDate.parse(d.take(10)) }.getOrNull() }
                    ?.let { "Due " + dayLabel(it, today, short = true) },
            )
            else -> CardProposal(a.kind, a.title)
        }
    }

    /** "person/me" as "You", "person/andrea-egan" as "Andrea Egan". */
    fun bodyName(id: String): String {
        if (id == "person/me") return "You"
        return id.substringAfter('/').split('-').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    }

    /** A card is read at a glance: a fortnight, a dozen tasks. */
    private const val MAX_DAYS = 14
    private const val MAX_TASKS = 12
    private const val MAX_ROWS = 8
}
