package io.nisfeb.talon.ai

import io.nisfeb.talon.calendar.CalendarRow
import io.nisfeb.talon.calendar.CalendarTask
import io.nisfeb.talon.calendar.daysOf
import io.nisfeb.talon.calendar.dueDate
import io.nisfeb.talon.orrery.DecideSettings
import io.nisfeb.talon.orrery.Decider
import io.nisfeb.talon.orrery.OpenRouterDecider
import io.nisfeb.talon.orrery.attempt
import io.nisfeb.talon.orrery.onVendor
import io.nisfeb.talon.orrery.openRouterKey
import io.nisfeb.talon.orrery.under
import io.ktor.client.HttpClient
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Instant

/**
 * "What's on my calendar this week?" answered on the phone, with no model:
 * the calendar is already read while the owner talks, so the card and a
 * spoken line come from it in no time. On the phone a model took 18 s of
 * its 21 to say what the card already showed. Only plain questions about
 * what is on, for a day or a few; anything else (with whom, when is X,
 * am I free, what is left to do before a trip) goes to the model, which
 * reads orrery too. The decision model tells which ([decide]).
 */
object CalendarQuestion {
    /** The days asked about, and how to say them: "today", "this week", "on Friday". */
    data class Span(val from: LocalDate, val to: LocalDate, val said: String, val next: Boolean = false) {
        val oneDay: Boolean get() = from == to
    }

    private val WEEKDAYS = DayOfWeek.entries.associateBy { it.name.lowercase() }

    /** How sure the decision model must be that the days are the whole answer: a wrong yes swallows a question, or a reminder. */
    const val SURE = 0.8

    /** How long the decision may take before the model is asked instead. */
    const val WAIT_MS = 3_000L

    /**
     * Asked of the decision model in one call: whether listing some days
     * answers the owner in full, and which days. sneagan, 2026-10-16:
     * "instead of hard coded phrases can we use the decision model to
     * decide whether the answer takes the fast local path or the smart
     * one?" A word list had answered "What do I have left to do before
     * Barcelona?" with the day's events.
     */
    val QUESTIONS: JsonObject = buildJsonObject {
        putJsonObject("calendar_only") {
            put("type", "noul")
            put("instructions", "Is the owner only asking what is on their calendar on some days, or what is next on it, so that those days' events and due tasks answer them in full?")
            putJsonObject("criteria") {
                put("true", "a plain question about what is on: what's on today, what do I have tomorrow, how does my week look, anything this weekend, what's next, or a bare follow-up such as \"and tomorrow?\" to one of these")
                put("false", "anything else: an instruction or a request (add, remind, text, send, cancel, move, something to do or to keep), what is left to do, a trip, a person, a place, when or where something is, being free or busy, a count, mail or messages")
            }
        }
        putJsonObject("days") {
            put("type", "choice")
            put("instructions", "Which days does the owner ask about? The state gives today.")
            putJsonObject("criteria") {
                put("today", "today, tonight, this morning, my day, or no day named")
                put("tomorrow", "tomorrow")
                put("this_week", "this week: today and the six days after it")
                put("this_weekend", "this weekend")
                put("next_week", "next week, Monday to Sunday")
                put("next", "only the next thing on the calendar")
                WEEKDAYS.keys.forEach { put(it, "the coming ${it.replaceFirstChar { c -> c.uppercase() }}, today if today is one") }
                put("other", "any other day or span: a date, a month, a day already past")
            }
        }
    }

    /** What the decision model reads: the words, the exchange before them, and today. */
    fun state(text: String, earlier: List<DictationFlow.Earlier>, today: LocalDate): JsonObject = buildJsonObject {
        put("question", text)
        putJsonArray("earlier") { earlier.forEach { add("owner: ${it.words}"); add("answer: ${it.line}") } }
        put("today", "${today.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }}, $today")
    }

    /**
     * The days [text] asks what is on, as the decision model judged it, or
     * null for the smart path: not only the calendar, not sure enough, no
     * answer in time, or days it could not name. [log] gets what it said.
     */
    suspend fun decide(decider: Decider, text: String, earlier: List<DictationFlow.Earlier>, today: LocalDate, log: (String) -> Unit = {}): Span? {
        val d = decider.attempt(state(text, earlier, today), QUESTIONS)
            .getOrElse { log("decision model did not answer: ${it.message?.take(160)}"); return null }
        val p = ((d.answers["calendar_only"] as? JsonObject)?.get("noul") as? JsonPrimitive)?.doubleOrNull
        val days = ((d.answers["days"] as? JsonObject)?.get("choice") as? JsonPrimitive)?.contentOrNull
        log("decision model: calendar only ${p ?: "?"}, days ${days ?: "?"}")
        if (p == null || p < SURE || days == null) return null
        return spanOf(days, today)
    }

    /**
     * The decision model for [decide], where the owner turned it on and
     * there is a key, waiting [waitMs] at most. Null, and every question
     * goes to the model.
     */
    fun decider(http: HttpClient, cfg: AiSettings.Config, waitMs: Long = WAIT_MS): Decider? {
        val d = DecideSettings().under(cfg).onVendor(cfg).takeIf { it.on } ?: return null
        return openRouterKey(cfg)?.let { OpenRouterDecider(http, it, d.copy(timeoutMs = waitMs)) }
    }

    /** The days [choice] names, from [today]; null for "other" or a choice not offered. */
    fun spanOf(choice: String, today: LocalDate): Span? {
        WEEKDAYS[choice]?.let { day ->
            val ahead = (day.isoDayNumber - today.dayOfWeek.isoDayNumber + 7) % 7
            val d = today.plus(ahead, DateTimeUnit.DAY)
            return Span(d, d, if (ahead == 0) "today" else "on ${choice.replaceFirstChar { it.uppercase() }}")
        }
        return when (choice) {
            "today" -> Span(today, today, "today")
            "tomorrow" -> today.plus(1, DateTimeUnit.DAY).let { Span(it, it, "tomorrow") }
            "this_week" -> Span(today, today.plus(6, DateTimeUnit.DAY), "this week")
            "this_weekend" -> {
                val sat = today.plus((DayOfWeek.SATURDAY.isoDayNumber - today.dayOfWeek.isoDayNumber + 7) % 7, DateTimeUnit.DAY)
                val from = if (today.dayOfWeek == DayOfWeek.SUNDAY) today else sat
                Span(from, if (today.dayOfWeek == DayOfWeek.SUNDAY) today else sat.plus(1, DateTimeUnit.DAY), "this weekend")
            }
            "next_week" -> {
                val monday = today.plus(7 - (today.dayOfWeek.isoDayNumber - 1), DateTimeUnit.DAY)
                Span(monday, monday.plus(6, DateTimeUnit.DAY), "next week")
            }
            "next" -> Span(today, today.plus(7, DateTimeUnit.DAY), "next", next = true)
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
