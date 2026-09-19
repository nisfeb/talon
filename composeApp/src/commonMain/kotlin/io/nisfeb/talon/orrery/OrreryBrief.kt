package io.nisfeb.talon.orrery

import io.nisfeb.talon.calendar.CalendarRow
import io.nisfeb.talon.calendar.CalendarTask
import io.nisfeb.talon.mail.MailMessage
import io.nisfeb.talon.mail.MailThread
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The daily brief: one mail each morning from the owner to the owner,
 * and the owner's reply read back once.
 *
 * Three parts. Today: the calendar's day, with what orrery expects that
 * the calendar does not show, then the todos. Waiting on you: every
 * proposed action under a short tag. Suggestions: the frontier model's
 * few lines on the state, which file nothing.
 *
 * The reply carries directions on the tags and, around them, facts in
 * the owner's own words. The brief's text is never read as facts: what
 * is quoted, and any line the brief itself said, is stripped first.
 *
 * Everything here is pure; OrreryRepo does the sending and the writes.
 */
object Brief {
    const val HOUR = 7
    /** A device asleep at seven sends on waking, within the hour; later than that, no brief that day. */
    const val LATE_MS = 60 * 60_000L
    const val DEFAULT_ZONE = "America/New_York"
    const val MAX_UNDATED = 10
    private const val PREFIX = "Daily brief "

    fun subject(day: LocalDate): String = PREFIX + day

    /** The day a brief's subject names, through any "Re:". */
    fun dayOf(subject: String): LocalDate? =
        Regex("""Daily brief (\d{4}-\d{2}-\d{2})""").find(subject)?.groupValues?.get(1)
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** The day a brief is due for, when it is due now: seven in the owner's zone, or within the hour after. */
    fun dueDay(nowMs: Long, zone: TimeZone): LocalDate? {
        val date = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone).date
        val at = sevenOn(date, zone)
        return if (nowMs in at until at + LATE_MS) date else null
    }

    /** How long until the next seven o'clock in the owner's zone, so the loop can wake for it. */
    fun untilNext(nowMs: Long, zone: TimeZone): Long {
        val date = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone).date
        val today = sevenOn(date, zone)
        return (if (nowMs < today) today else sevenOn(date.plus(1, DateTimeUnit.DAY), zone)) - nowMs
    }

    private fun sevenOn(date: LocalDate, zone: TimeZone): Long =
        LocalDateTime(date.year, date.monthNumber, date.dayOfMonth, HOUR, 0).toInstant(zone).toEpochMilliseconds()

    // ---- the raw state view -----------------------------------------------

    fun bodies(state: JsonObject): List<JsonObject> =
        (state["bodies"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull

    /** An attribute's current value: one, or a list of them for a multi. */
    fun value(body: JsonObject, attr: String): JsonElement? =
        when (val v = (body["attrs"] as? JsonObject)?.get(attr)) {
            is JsonObject -> v["value"]
            is JsonArray -> JsonArray(v.mapNotNull { (it as? JsonObject)?.get("value") })
            else -> null
        }

    fun text(body: JsonObject, attr: String): String? = (value(body, attr) as? JsonPrimitive)?.contentOrNull

    /** person/me's timezone on the ship, which wins over the default. */
    fun zone(state: JsonObject): TimeZone {
        val me = state.str("me") ?: "person/me"
        val id = bodies(state).firstOrNull { it.str("id") == me }?.let { text(it, "timezone") }
        return runCatching { TimeZone.of(id ?: DEFAULT_ZONE) }.getOrElse { TimeZone.of(DEFAULT_ZONE) }
    }

    fun names(state: JsonObject): Map<String, String> =
        bodies(state).mapNotNull { b -> b.str("id")?.let { it to (b.str("name") ?: it) } }.toMap()

    // ---- part one: today ----------------------------------------------------

    private data class Slot(val startMs: Long, val allDay: Boolean, val text: String)

    /**
     * The day in order: all-day entries, then timed ones by start, then
     * todos. The calendar is the schedule; a situation starting today or
     * an activity whose next is today joins it only when the calendar
     * has nothing by that title.
     */
    fun today(
        day: LocalDate,
        zone: TimeZone,
        events: List<CalendarRow>,
        todos: List<CalendarTask>,
        state: JsonObject,
    ): List<String> {
        val from = day.atStartOfDayIn(zone).toEpochMilliseconds()
        val to = day.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds()
        val slots = mutableListOf<Slot>()
        val onCalendar = mutableSetOf<String>()
        for (r in events) {
            if (r.isTask || r.name.isBlank() || r.r <= from || r.l >= to) continue
            val where = r.location.takeIf { it.isNotBlank() }?.let { ", $it" }.orEmpty()
            slots += Slot(r.l, r.all || (r.l <= from && r.r >= to), r.name + where)
            onCalendar += normalizeTitle(r.name)
        }
        for (b in bodies(state)) {
            val id = b.str("id") ?: continue
            val at = when {
                id.startsWith("situation/") ->
                    if (text(b, "status") in setOf("closed", "cancelled")) continue else text(b, "starts")
                id.startsWith("activity/") -> text(b, "next")
                else -> continue
            }?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() } ?: continue
            val name = b.str("name") ?: id
            if (at !in from until to || normalizeTitle(name) in onCalendar) continue
            slots += Slot(at, false, name)
            onCalendar += normalizeTitle(name)
        }
        val lines = slots.sortedWith(compareBy({ !it.allDay }, { it.startMs }))
            .map { (if (it.allDay) "All day" else clock(it.startMs, zone)) + "  " + it.text }
            .toMutableList()
        // A todo's due is midnight UTC of its day.
        val open = todos.filter { it.cat == "todo" && !it.done && it.name.isNotBlank() }
        fun dueDate(t: CalendarTask) = t.dueMs?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date }
        open.filter { t -> dueDate(t)?.let { it <= day } == true }.sortedBy { it.dueMs }.forEach { t ->
            lines += "To do  " + t.name + if (dueDate(t)!! < day) " (overdue)" else ""
        }
        val undated = open.filter { it.dueMs == null }
        undated.take(MAX_UNDATED).forEach { lines += "To do  " + it.name }
        if (undated.size > MAX_UNDATED) lines += "and ${undated.size - MAX_UNDATED} more to do"
        return lines
    }

    fun clock(ms: Long, zone: TimeZone): String {
        val t = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone)
        return "${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
    }

    private fun titled(s: String) = s.lowercase().replaceFirstChar { it.uppercase() }

    // ---- part two: waiting on you ------------------------------------------

    /** Every proposed action as lines under a tag, and the tags to their ids. */
    fun waiting(actions: List<OrreryAction>, zone: TimeZone, names: Map<String, String>): Pair<List<String>, Map<String, String>> {
        val tags = linkedMapOf<String, String>()
        val lines = mutableListOf<String>()
        actions.filter { it.status == "proposed" }.forEachIndexed { i, a ->
            val tag = "A${i + 1}"
            tags[tag] = a.id
            lines += "[$tag] ${a.title}"
            val bits = listOfNotNull(
                a.kind,
                a.about.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "about ") { names[it] ?: it },
                a.due?.let { d -> runCatching { "due " + whenText(Instant.parse(d).toEpochMilliseconds(), zone) }.getOrNull() },
            )
            lines += "     " + bits.joinToString(", ")
            (a.payload["why"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { lines += "     Why: $it" }
        }
        return lines to tags
    }

    private fun whenText(ms: Long, zone: TimeZone): String {
        val t = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone)
        return titled(t.dayOfWeek.name).take(3) + " " + t.dayOfMonth + " " + titled(t.month.name).take(3) + " " + clock(ms, zone)
    }

    // ---- part three: suggestions ------------------------------------------

    /** The frontier model's brief, framed the way common/generator-prompt.md frames the analyst. */
    val SYSTEM: String = """
        You are the analyst for orrery, a model of one person's world kept on their own ship. Each morning you write the owner a few lines to read on their phone before the day starts.
        You are given the state (every body with its current attributes), the day's schedule and todos, the actions waiting for the owner's answer, the recent decisions, and the time now.
        Point out what the owner would want to know and might not see: two things in the day that overlap or leave no time between them, a fact that looks stale or wrong, something open with nothing being done about it, a decision that is waiting on them.
        Do not list the schedule or the waiting actions again; the mail already does. Do not propose actions; another pass does that.
        Respect what the facts say about time: an occurrence in the past is over, and a situation that is upcoming has not happened.
        Do not invent facts, people, places or events. Do not moralise.
        Plain text, no markdown, at most six short lines, one thing each. When there is nothing worth saying, answer exactly: Nothing to add.
    """.trimIndent()

    /** What generator/run.py's build_parts shows, least changing first, with the day and the waiting actions added. */
    fun statePrompt(
        state: JsonObject,
        decided: List<OrreryAction>,
        nowIso: String,
        zone: TimeZone,
        today: List<String>,
        waiting: List<String>,
    ): String = buildString {
        appendLine("The owner is ${state.str("me") ?: "person/me"}.")
        val all = bodies(state).take(MAX_BODIES)
        val hidden = all.filter { b -> b.str("id").orEmpty().startsWith("situation/") && phase(b, nowIso) in setOf("closed", "cancelled", "over") }
            .map { it.str("id") }.toSet()
        for (kind in listOf("thing", "place", "org", "note", "person", "activity", "situation")) {
            val rows = all.filter { it.str("kind") == kind && it.str("id") !in hidden }
            if (rows.isEmpty()) continue
            appendLine(if (kind == "activity") "activities:" else "${kind}s:")
            rows.forEach { appendLine("  " + line(it, nowIso)) }
        }
        appendLine("Recent decisions:")
        decided.takeLast(RECENT).forEach { appendLine("  ${it.status} | ${it.kind} | ${it.title}") }
        appendLine("Today's schedule and todos:")
        (today.ifEmpty { listOf("nothing") }).forEach { appendLine("  $it") }
        appendLine("Waiting on the owner:")
        (waiting.ifEmpty { listOf("nothing") }).forEach { appendLine("  $it") }
        append("Now: $nowIso, timezone ${zone.id}. Write the brief.")
    }

    private const val MAX_BODIES = 300
    private const val RECENT = 60

    internal fun phase(b: JsonObject, now: String): String {
        val st = text(b, "status")
        if (st == "closed" || st == "cancelled") return st
        val end = text(b, "ended") ?: text(b, "ends")
        val start = text(b, "started") ?: text(b, "starts")
        return when {
            end != null && end <= now -> "over"
            start != null && start <= now -> "under way"
            start != null -> "upcoming"
            else -> st ?: "open"
        }
    }

    internal fun line(b: JsonObject, now: String): String {
        val attrs = (b["attrs"] as? JsonObject).orEmpty()
        val bits = attrs.keys.sorted().mapNotNull { k ->
            val v = value(b, k) ?: return@mapNotNull null
            if (v is JsonNull) return@mapNotNull null
            val shown = when (v) {
                is JsonArray -> v.joinToString(", ") { refOrText(it) }
                else -> refOrText(v)
            }
            "$k=" + shown.split(Regex("\\s+")).joinToString(" ").take(120)
        }
        var head = "${b.str("id")} | ${b.str("name").orEmpty()}"
        if (b.str("id").orEmpty().startsWith("situation/")) head += " | " + phase(b, now)
        return head + if (bits.isEmpty()) "" else " | " + bits.joinToString("; ")
    }

    private fun refOrText(v: JsonElement): String =
        (v as? JsonObject)?.str("ref") ?: (v as? JsonPrimitive)?.contentOrNull ?: v.toString()

    fun render(day: LocalDate, today: List<String>, waiting: List<String>, suggestions: String): String = buildString {
        appendLine("Today, ${titled(day.dayOfWeek.name)} ${day.dayOfMonth} ${titled(day.month.name)}")
        appendLine()
        if (today.isEmpty()) appendLine("Nothing on the calendar.") else today.forEach(::appendLine)
        appendLine()
        appendLine("Waiting on you")
        if (waiting.isEmpty()) {
            appendLine("Nothing.")
        } else {
            waiting.forEach(::appendLine)
            appendLine()
            appendLine("Reply with \"approve A1\", \"dismiss A2\", \"A3 done\" or \"A1 due friday\".")
        }
        appendLine()
        appendLine("Suggestions")
        appendLine(suggestions.trim())
        appendLine()
        append("Anything else you write back is recorded as a fact, in your words.")
    }

    // ---- the reply -----------------------------------------------------------

    /** The brief a thread started with, when it is one of ours. */
    fun briefOf(thread: MailThread, me: String): MailMessage? =
        thread.messages.firstOrNull { it.prev == null && it.from == me && dayOf(it.subject) != null }

    /** The owner's replies in a brief's thread not handled yet. Anyone else's words are not the owner's. */
    fun pendingReplies(thread: MailThread, me: String, handled: Set<String>): List<MailMessage> {
        val brief = briefOf(thread, me) ?: return emptyList()
        return thread.messages.filter { it.id != brief.id && it.from == me && it.prev != null && it.id !in handled }
            .sortedBy { it.sent }
    }

    private val ATTRIBUTION = Regex("""^On .{3,200}wrote:$""")

    /**
     * The reply's own words. Quoted lines go, the attribution and all
     * after it go, and so does any line the brief itself said: its text
     * is never read back as something the owner told us.
     */
    fun ownWords(reply: String, brief: String): String {
        val said = brief.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val out = mutableListOf<String>()
        for (raw in reply.lines()) {
            val l = raw.trim()
            if (ATTRIBUTION.matches(l) || l.startsWith("-----Original Message")) break
            if (l.startsWith(">") || l in said) continue
            out += raw
        }
        return out.joinToString("\n").trim()
    }

    /** What the owner said to do about one action: its new status, due or subject, by body id. */
    data class Direction(val actionId: String, val status: String? = null, val dueMs: Long? = null, val about: List<String>? = null)

    private val MOVES = setOf("approved", "dismissed", "done")

    /**
     * The model's moves, held to the brief: a tag it gave, a status the
     * owner may give, a due that parses, and a subject of bodies that
     * exist. What fails is dropped, never guessed at.
     */
    fun movesOf(answer: JsonObject, tags: Map<String, String>, known: Set<String>): List<Direction> {
        val out = linkedMapOf<String, Direction>()
        for (e in (answer["moves"] as? JsonArray).orEmpty()) {
            val m = e as? JsonObject ?: continue
            val id = m.str("tag")?.uppercase()?.trim('[', ']', ' ')?.let { tags[it] } ?: continue
            val status = m.str("status")?.lowercase()?.takeIf { it in MOVES }
            val due = m.str("due")?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }
            val about = (m["about"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.lowercase() }
                ?.takeIf { it.isNotEmpty() && it.all { b -> b in known } }
            if (status == null && due == null && about == null) continue
            val d = out[id] ?: Direction(id)
            out[id] = d.copy(status = status ?: d.status, dueMs = due ?: d.dueMs, about = about ?: d.about)
        }
        return out.values.toList()
    }

    /**
     * What the owner asked to have done, as /act bodies, checked the way
     * generator/run.py checks a proposal: a kind the schema lists, a
     * title, bodies that exist, and every payload key the schema marks
     * required.
     */
    fun replyActions(answer: JsonObject, schema: JsonObject?, known: Set<String>): List<JsonObject> {
        val kinds = schema?.let(::schemaActions).orEmpty().toSet()
        val payloads = (schema?.get("payloads") as? JsonObject).orEmpty()
        return (answer["actions"] as? JsonArray).orEmpty().mapNotNull { e ->
            val a = e as? JsonObject ?: return@mapNotNull null
            val kind = a.str("kind")?.lowercase()?.takeIf { it in kinds } ?: return@mapNotNull null
            val title = a.str("title")?.trim()?.take(200)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val about = (a["about"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.lowercase() }
            if (!about.all { it in known }) return@mapNotNull null
            val payload = a["payload"] as? JsonObject ?: JsonObject(emptyMap())
            val required = (payloads[kind] as? JsonObject).orEmpty()
                .filter { (_, v) -> (v as? JsonPrimitive)?.contentOrNull?.startsWith("required") == true }.keys
            if (!required.all { it in payload }) return@mapNotNull null
            buildJsonObject {
                put("kind", kind)
                put("title", title)
                putJsonArray("about") { about.take(20).forEach { add(it) } }
                a.str("due")?.let { d -> runCatching { Instant.parse(d) }.getOrNull()?.let { put("due", it.toString()) } }
                put("payload", payload)
            }
        }
    }

    /**
     * The status moves that take an action from where it stands to what
     * the owner said. A proposal is approved on its way to done; a
     * decided action stays decided.
     */
    fun steps(current: String, want: String): List<String> = when {
        current in setOf("done", "dismissed", "failed") || current == want -> emptyList()
        current == "proposed" && want == "done" -> listOf("approved", "done")
        current == "claimed" && want == "approved" -> emptyList()
        else -> listOf(want)
    }

    /**
     * The action that replaces [old] with a new due or subject. The
     * action API moves a status and nothing else, so a change is the old
     * one dismissed and this one proposed, with only the fields /act
     * takes.
     */
    fun replacement(old: OrreryAction, dueMs: Long?, about: List<String>?): JsonObject = buildJsonObject {
        put("kind", old.kind)
        put("title", old.title)
        putJsonArray("about") { (about ?: old.about).forEach { add(it) } }
        (dueMs?.let { isoUtc(it) } ?: old.due)?.let { put("due", it) }
        put("payload", old.payload)
    }

    // ---- the reply's facts -------------------------------------------------------

    /** common/analyst-prompt.md from orrery-utils, word for word: the owner's words are triaged as any message is. */
    val ANALYST: String = """
        You turn messages into facts for orrery, a model of one person's world.
        Three shapes exist.
        A body is something that exists: a person, place, thing, org, situation, activity or note. Its id is kind/slug, lowercase letters, digits and hyphens, for example person/sarah, place/johns-machine-shop, thing/subaru, situation/2026-09-16-breakdown.
        An observation is one claim about one body: subject.attr = value, with when it became true. Values are a short string, a number, true or false, null (which clears the attribute), or {"ref": "kind/slug"} pointing at another body.
        An action is something to do: a task with a title, the bodies it is about, and an optional due time.
        Rules.
        Only state what the messages say or clearly imply. Never invent. When unsure, leave it out or lower the confidence.
        Use the existing bodies by id whenever a message refers to one of them, by name or alias. When a message calls an existing body by a name the list does not have ("next door" for place/neighbors, "the Hendersons"), repeat that body in "bodies" with the new name under "aliases", so the ship learns the word. Create a new body only for a named person, place, thing or org, or for a situation (an event with participants) the messages describe.
        Use only the attribute names listed for that kind; an observation on any other name is dropped. When a kind has no attributes listed, use a short lowercase name. A health fact goes on "health" and a money fact on "income", never on a name of your own.
        Read the notes given with the attribute names: they say what each one means. A person's "status" is what they are doing or dealing with right now, in plain words, as an observer would put it: "on jury duty", "stranded, waiting for a tow", "travelling", "sick". It is never a feeling, a quote or a wish. A feeling goes under "mood", which the reader throws away, so that it never lands on status. A status is specific enough that someone who reads only it knows what is going on: "training for the Chicago marathon", not "on a strict regimen"; "in meetings", not "busy". When the messages do not say what it is, write no status. A status that ends at a stated time carries "until".
        Worked examples. "jury duty makes me want to scream", from Sarah: person/sarah.status = "on jury duty" (conf 80), person/sarah.mood = "frustrated" (conf 60, discarded). "car died on route 9, stranded waiting for a tow": status = "stranded, waiting for a tow", location = "Route 9", thing/subaru.status = "broken down". "stuck in meetings till 11:30", from Sarah at 2026-08-19T10:03:00-04:00: person/sarah.status = "in meetings", until = "2026-08-19T11:30:00-04:00". "ugh, Mondays": nothing.
        A situation body carries participants (one observation per participant, value {"ref": ...}), location, and its times: "starts" and "ends" are the schedule (a meeting on December 5 has starts and ends on December 5, even today), "started" and "ended" are facts about what happened, written only once it has. Its status is "open" or "closed" (or "cancelled"), nothing else: never "upcoming", "under way" or "over", which are read off the times. A situation happens once: a breakdown, a birthday, a delivery.
        An activity is something that repeats: a class, a practice, a standing appointment, a weekly meeting. It is one body of kind activity, with schedule ("Mon/Wed 18:00"), cadence ("weekly"), location, participants and organizer. An occurrence of an activity is never a new body: write the activity's "last" = the start of that occurrence, with "at" = that start, and "next" = the start of the following one when the message says it. A calendar reminder or notification for a repeating event is an occurrence of an activity, not a situation.
        Any part of an event can name a person: its title ("Mira- Ballet/Tap", "Theo and Juno- Opti Sail", "Felix Birthday"), its description ("bring Juno's helmet"), its attendee list, its organizer ("Coach Pat"), a note. Every person an event names is a participant of the activity or situation, and its organizer is its organizer. Resolve each name against the people listed; when nobody by that name exists, create the person, the first name (or the full name when the event gives it) as the body's name. A production, a team or a place is not a person: "Swan Lake rehearsal" and "Hornets practice" name no one.
        A person is never an org. A payment request, a reminder or a note from a person names a person body; reuse the existing person when the name or the address matches, even when only the first name is on record.
        "at" is when the fact became true, ISO 8601 with the offset the message times carry (they are in the owner's time zone), and defaults to the message's time; set it only when the message says otherwise. "until" is when it will stop being true, when the message says so.
        "conf" is 0 to 100: 90 for a plain statement, 60 for an inference, 40 for a guess.
        Each observation and action names the "message" id it comes from.
        Messages marked as earlier context are there so you understand the new ones: a reply, a pronoun, a mood that carries over. Write facts only from the new messages; anything you write from a context message is thrown away.
        Answer with one JSON object and nothing else:
        {"bodies": [{"id": "kind/slug", "name": "...", "aliases": ["..."]}],
         "observations": [{"subject": "kind/slug", "attr": "...", "value": ..., "at": "...", "until": "...", "conf": 90, "message": "..."}],
         "actions": [{"kind": "task", "title": "...", "about": ["kind/slug"], "due": "...", "message": "..."}]}
        Empty lists are fine. Small talk, greetings and things already known produce nothing.
    """.trimIndent()

    /**
     * The analyst prompt, then what a reply to the brief adds: it is the
     * owner speaking, and a sentence about a tagged action is a move on
     * it rather than a fact.
     */
    val REPLY_SYSTEM: String get() = ANALYST + "\n\n" + REPLY_RULES

    private val REPLY_RULES = """
        This message is the owner's reply to their daily brief. It is the owner speaking about their own world, so a plain statement in it is conf 100.
        The brief listed actions waiting for the owner's answer, each under a tag such as A1; they are given below with what each one is. A sentence about a tagged action is a move on it, not a fact: answer it under "moves", one per action, with only what the owner changed: {"tag": "A1", "status": "approved", "due": "...", "about": ["kind/slug"]}. The status is "approved" (approve, yes, go ahead), "dismissed" (dismiss, no, skip) or "done" (done, did it). A new due is ISO 8601 UTC, read in the owner's timezone from the time now. A new subject names existing bodies by id. Write no fact from a sentence that only moves an action.
        Everything else in the reply is facts, by the rules above. Something the owner asks to have done is an action.
        Answer with one JSON object and nothing else:
        {"moves": [...], "bodies": [...], "observations": [...], "actions": [...]}
    """.trimIndent()

    /** The context block common/analyze.py's prompt builds, for one message from the owner, with the brief's tags and the clock. */
    fun analystPrompt(
        state: JsonObject,
        attrs: Map<String, List<String>>,
        notes: Map<String, Map<String, String>>,
        replyId: String,
        atMs: Long,
        text: String,
        tagged: Map<String, OrreryAction> = emptyMap(),
        nowMs: Long = atMs,
        zone: TimeZone = TimeZone.UTC,
    ): String = buildString {
        appendLine("Channel: mail")
        appendLine("The owner is ${state.str("me") ?: "person/me"}.")
        if (attrs.isNotEmpty()) {
            appendLine("Attribute names by kind:")
            attrs.forEach { (k, names) -> appendLine("  $k: ${names.joinToString(", ")}") }
        }
        if (notes.isNotEmpty()) {
            appendLine("What the attributes mean:")
            notes.forEach { (k, byAttr) -> byAttr.forEach { (a, n) -> appendLine("  $k.$a: $n") } }
        }
        appendLine("Existing bodies (id | name | aliases):")
        val all = bodies(state).take(MAX_BODIES)
        if (all.isEmpty()) appendLine("  (none known)")
        all.forEach { b ->
            val aliases = (b["aliases"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            appendLine("  ${b.str("id")} | ${b.str("name").orEmpty()} | ${aliases.joinToString(", ")}")
        }
        if (tagged.isNotEmpty()) {
            appendLine("Actions in the brief, by tag (tag | kind | title | about | due | status):")
            tagged.forEach { (t, a) ->
                appendLine("  $t | ${a.kind} | ${a.title} | ${a.about.joinToString(", ")} | ${a.due ?: "no due"} | ${a.status}")
            }
        }
        appendLine("Now: ${isoUtc(nowMs)}, timezone ${zone.id}.")
        appendLine()
        appendLine("Messages, oldest first:")
        appendLine("--- message $replyId | ${isoUtc(atMs)} | from ${state.str("me") ?: "person/me"}")
        appendLine(text.take(4000))
        appendLine("---")
        append("Answer with the JSON object.")
    }

    /** The first JSON object in a model's answer, fences and chatter stripped. */
    fun parseAnswer(text: String): JsonObject? {
        val t = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```")
        val start = t.indexOf('{')
        val end = t.lastIndexOf('}')
        if (start < 0 || end < start) return null
        return runCatching { Json.parseToJsonElement(t.substring(start, end + 1)).jsonObject }.getOrNull()
    }

    private val ID = Regex("""^[a-z]+/[a-z0-9][a-z0-9-]*$""")

    /**
     * The answer as facts, held to the ship. A body the ship has under
     * another id ([resolved], asked before anything is made) is that
     * body; one it has is only taught its new names; one it lacks is
     * made. Each observation is on a body that exists or is made here,
     * under an attribute the schema gives that kind, pointing only at
     * bodies that exist. The owner is speaking, so conf is 100, and the
     * source is the reply. What the owner asked to have done is
     * [replyActions]'s.
     */
    fun replyFacts(
        answer: JsonObject,
        known: Set<String>,
        attrs: Map<String, List<String>>,
        resolved: Map<String, String>,
        replyId: String,
        atMs: Long,
    ): Facts {
        fun target(id: String) = resolved[id] ?: id
        val bodies = mutableListOf<OBody>()
        val made = mutableSetOf<String>()
        for (e in (answer["bodies"] as? JsonArray).orEmpty()) {
            val b = e as? JsonObject ?: continue
            val raw = b.str("id")?.lowercase() ?: continue
            val id = target(raw)
            if (!ID.matches(id) || id.substringBefore('/') !in attrs) continue
            val aliases = (b["aliases"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            if (id in known) {
                // Names only: the ship keeps its own name, and an upsert of
                // aliases alone unions them.
                val learn = (aliases + listOfNotNull(b.str("name").takeIf { raw != id })).distinct()
                if (learn.isNotEmpty()) bodies += OBody(id, aliases = learn)
            } else {
                bodies += OBody(id, b.str("name"), aliases)
                made += id
            }
        }
        val exists = known + made
        val obs = mutableListOf<Obs>()
        for (e in (answer["observations"] as? JsonArray).orEmpty()) {
            val o = e as? JsonObject ?: continue
            val subject = o.str("subject")?.lowercase()?.let(::target) ?: continue
            val attr = o.str("attr")?.lowercase() ?: continue
            if (subject !in exists || attr == "mood" || attr !in attrs[subject.substringBefore('/')].orEmpty()) continue
            val value: JsonElement = when (val v = o["value"]) {
                null, is JsonNull -> JsonNull
                is JsonPrimitive -> v
                is JsonObject -> v.str("ref")?.lowercase()?.let(::target)?.takeIf { it in exists }
                    ?.let { buildJsonObject { put("ref", it) } } ?: continue
                else -> continue
            }
            fun ms(k: String) = o.str(k)?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }
            obs += Obs(subject, attr, value, ms("at") ?: atMs, ms("until"), conf = 100, sourceKind = "mail", sourceId = replyId)
        }
        return Facts(bodies, obs)
    }
}
