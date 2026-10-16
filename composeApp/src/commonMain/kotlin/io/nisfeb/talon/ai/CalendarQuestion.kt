package io.nisfeb.talon.ai

import io.nisfeb.talon.calendar.CalendarRow
import io.nisfeb.talon.calendar.CalendarTask
import io.nisfeb.talon.calendar.daysOf
import io.nisfeb.talon.calendar.dueDate
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * "What's on my calendar this week?" answered on the phone, with no model:
 * the calendar is already read while the owner talks, so the card and a
 * spoken line come from it in no time. On the phone a model took 18 s of
 * its 21 to say what the card already showed. Only plain questions about
 * what is on, for a day or a few; anything else (with whom, when is X,
 * am I free, what is left to do before a trip) goes to the model, which
 * reads orrery too.
 */
object CalendarQuestion {
    /** The days asked about, and how to say them: "today", "this week", "on Friday". */
    data class Span(val from: LocalDate, val to: LocalDate, val said: String, val next: Boolean = false) {
        val oneDay: Boolean get() = from == to
    }

    private val ASKS = listOf(
        Regex("""\bwhat(?:'s| is| do i have)? (?:on|happening|going on|planned|scheduled|coming up)\b"""),
        Regex("""\bwhat do i have\b"""),
        Regex("""\b(?:do|have) i (?:have|got) anything\b"""),
        Regex("""\b(?:what|how)(?:'s| is| does| do)? my (?:day|week|weekend|schedule|calendar|agenda|morning|afternoon|evening)\b"""),
        Regex("""^(?:my |the )?(?:calendar|schedule|agenda)\b"""),
        Regex("""\bwhat(?:'s| is) (?:next|my next (?:event|meeting|thing|appointment))\b"""),
    )

    private val WEEKDAYS = DayOfWeek.entries.associateBy { it.name.lowercase() }

    /**
     * Every word a plain "what's on" question is made of. One more word
     * ("left", "done", "Barcelona", "with") asks what the card cannot
     * say, and the model takes it: "What do I have left to do before
     * Barcelona?" was answered with the day's events (sneagan, 2026-10-16).
     */
    // ponytail: a word list, so a calendar question with a stray word
    // waits ~5 s for the model instead of ~2 s here. The safe way round.
    private val PLAIN = setOf(
        "what", "what's", "whats", "how", "how's", "hows", "is", "do", "does", "i", "have", "got", "anything",
        "on", "happening", "going", "planned", "scheduled", "coming", "up", "look", "looks", "like",
        "my", "the", "for", "and", "hey", "so", "then", "ok", "okay", "please", "talon",
        "day", "week", "weekend", "schedule", "calendar", "agenda", "morning", "afternoon", "evening",
        "next", "event", "meeting", "thing", "appointment", "today", "tonight", "tomorrow", "this",
    ) + WEEKDAYS.keys

    /**
     * The days [text] asks what is on, or null where it is not plainly
     * that. After an [earlier] calendar question a bare "and tomorrow?"
     * asks it again for other days.
     */
    fun parse(text: String, today: LocalDate, earlier: String? = null): Span? {
        val t = text.lowercase().replace('’', '\'').replace(Regex("""[^a-z0-9' ]"""), " ").replace(Regex("""\s+"""), " ").trim()
        if (t.isEmpty() || t.split(' ').any { it !in PLAIN }) return null
        val asked = ASKS.any { it.containsMatchIn(t) }
        val followUp = !asked && earlier != null && parse(earlier, today) != null && t.split(' ').size <= 5
        if (!asked && !followUp) return null
        if (Regex("""\bwhat(?:'s| is) (?:next|my next)""").containsMatchIn(t)) return Span(today, today.plus(7, DateTimeUnit.DAY), "next", next = true)
        return span(t, today) ?: if (asked) Span(today, today, "today") else null
    }

    private fun span(t: String, today: LocalDate): Span? {
        val week = Regex("""\b(this|next) week\b|\bmy week\b|\bthe week\b""").find(t)
        WEEKDAYS.entries.firstOrNull { (name, _) -> Regex("""\b$name\b""").containsMatchIn(t) }?.let { (name, day) ->
            var ahead = (day.isoDayNumber - today.dayOfWeek.isoDayNumber + 7) % 7
            if (ahead == 0 && Regex("""\bnext $name\b""").containsMatchIn(t)) ahead = 7
            val d = today.plus(ahead, DateTimeUnit.DAY)
            return Span(d, d, if (ahead == 0) "today" else "on ${name.replaceFirstChar { it.uppercase() }}")
        }
        return when {
            Regex("""\b(today|tonight|this morning|this afternoon|this evening|my day)\b""").containsMatchIn(t) -> Span(today, today, "today")
            Regex("""\btomorrow\b""").containsMatchIn(t) -> today.plus(1, DateTimeUnit.DAY).let { Span(it, it, "tomorrow") }
            Regex("""\b(this |the |my )?weekend\b""").containsMatchIn(t) -> {
                val sat = today.plus((DayOfWeek.SATURDAY.isoDayNumber - today.dayOfWeek.isoDayNumber + 7) % 7, DateTimeUnit.DAY)
                val from = if (today.dayOfWeek == DayOfWeek.SUNDAY) today else sat
                Span(from, if (today.dayOfWeek == DayOfWeek.SUNDAY) today else sat.plus(1, DateTimeUnit.DAY), "this weekend")
            }
            week?.groupValues?.get(1) == "next" -> {
                val monday = today.plus(7 - (today.dayOfWeek.isoDayNumber - 1), DateTimeUnit.DAY)
                Span(monday, monday.plus(6, DateTimeUnit.DAY), "next week")
            }
            week != null -> Span(today, today.plus(6, DateTimeUnit.DAY), "this week")
            else -> null
        }
    }

    /** The span as moments, for asking the calendar. */
    fun bounds(span: Span, zone: TimeZone): LongRange =
        span.from.atStartOfDayIn(zone).toEpochMilliseconds() until span.to.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds()

    /**
     * The spoken line for [span]: how many events, what is on now, what is
     * next, and the tasks due. The card shows each one; this is what a
     * driver hears. [rows] are the calendar's for the span or more.
     */
    fun answer(span: Span, rows: List<CalendarRow>, tasks: List<CalendarTask>, zone: TimeZone, nowMs: Long, twentyFour: Boolean): String {
        val today = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone).date
        val events = rows.filter { !it.isTask && daysOf(it, zone).any { d -> d in span.from..span.to } }.sortedBy { it.l }
        val due = tasks.count { t -> !t.done && t.dueDate()?.let { it in span.from..span.to || (span.from <= today && it < today) } == true }
        fun at(r: CalendarRow): String {
            if (r.all) return "all day"
            val day = Instant.fromEpochMilliseconds(r.l).toLocalDateTime(zone).date
            val clock = DictationCards.clock(r.l, zone, twentyFour)
            if (span.oneDay) return "at $clock"
            val word = DictationCards.dayLabel(day, today).let { if (it == "Today" || it == "Tomorrow") it.lowercase() else it.substringBefore(',') }
            return "$word at $clock"
        }
        val tasksLine = when (due) {
            0 -> ""
            1 -> " One task is due."
            else -> " $due tasks are due."
        }
        if (events.isEmpty()) return "Nothing on your calendar ${span.said}.$tasksLine"
        val now = events.firstOrNull { !it.all && it.l <= nowMs && nowMs < it.r }
        val next = events.firstOrNull { !it.all && it.l > nowMs }
        if (span.next) return (next?.let { "Next up, ${it.name.ifBlank { "an event" }} ${at(it)}." } ?: "Nothing else this week.") + tasksLine
        val count = if (events.size == 1) "one event" else "${events.size} events"
        val head = when {
            span.oneDay -> "${span.said.replaceFirstChar { it.uppercase() }} you have $count."
            else -> {
                val days = events.flatMap { daysOf(it, zone) }.filter { it in span.from..span.to }.distinct().size
                "${span.said.replaceFirstChar { it.uppercase() }}: $count over ${if (days == 1) "one day" else "$days days"}."
            }
        }
        val on = now?.let { " You're in ${it.name.ifBlank { "an event" }} until ${DictationCards.clock(it.r, zone, twentyFour)}." }.orEmpty()
        val upcoming = when {
            next != null && span.from <= today -> " Next is ${next.name.ifBlank { "an event" }} ${at(next)}."
            span.from > today -> events.first().let { " First is ${it.name.ifBlank { "an event" }} ${at(it)}." }
            else -> " The rest is done."
        }
        return head + on + upcoming + tasksLine
    }
}
