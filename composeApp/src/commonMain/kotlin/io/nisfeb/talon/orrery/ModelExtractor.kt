package io.nisfeb.talon.orrery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime
import io.nisfeb.talon.ui.parseIsoUtc
import io.nisfeb.talon.urbit.asStr

/**
 * One message in, claims out, through whatever model the ladder gave.
 *
 * The prompt shows the model the bodies the ship knows and asks for
 * JSON in one shape; the grammar makes that shape the only thing a
 * llama.cpp rung can say, and the parse holds every rung to it: an
 * attr in orrery's charset, a subject the ship has or the author
 * themselves, a ref only to a listed body, conf capped at 80. What
 * fails is dropped, never guessed at.
 */
object ModelExtractor {
    const val MAX_TOKENS = 256
    const val MAX_CONF = 80
    private const val HOUR_MS = 60L * 60 * 1000

    /** JSON, and only the answer's shape of it, in llama.cpp's GBNF. */
    val GRAMMAR: String = """
        root ::= "{" ws "\"claims\"" ws ":" ws "[" ws (claim (ws "," ws claim)*)? ws "]" ws ("," ws "\"plan\"" ws ":" ws (plan | "null") ws)? "}"
        plan ::= "{" ws "\"title\"" ws ":" ws string ws "," ws "\"starts\"" ws ":" ws string ws ("," ws "\"ends\"" ws ":" ws string ws)? ("," ws "\"location\"" ws ":" ws string ws)? "}"
        claim ::= "{" ws "\"subject\"" ws ":" ws string ws "," ws "\"attr\"" ws ":" ws string ws "," ws "\"value\"" ws ":" ws value ws "," ws "\"conf\"" ws ":" ws number ws ("," ws "\"until_hours\"" ws ":" ws number ws)? "}"
        value ::= string | "null" | "{" ws "\"ref\"" ws ":" ws string ws "}"
        string ::= "\"" ([^"\\] | "\\" (["\\/bfnrt] | "u" hex hex hex hex))* "\""
        hex ::= [0-9a-fA-F]
        number ::= "-"? [0-9]+ ("." [0-9]+)?
        ws ::= [ \t\n]*
    """.trimIndent()

    val SYSTEM: String = """
        You read one chat message and write down what it states is true now about the listed bodies. Answer with JSON only, in this shape: {"claims":[{"subject":"<id>","attr":"<word>","value":<value>,"conf":<0-100>,"until_hours":<number, optional>}],"plan":<optional, see below>}
        subject is copied exactly from the listed ids, or is the author's id. Only the author and the bodies the message names can be subjects. attr is one short lowercase word: status, location, phone, email, skipped. value is a short string; or {"ref":"<listed id>"} when it names a listed body; or null when something has stopped being true. conf is how sure you are. until_hours is how long a temporary claim holds, such as being somewhere. At most three claims.
        Claim only what the message states as fact about now. A question, a joke, a wish, a plan, or the past is nothing: {"claims":[]}.
        conf is 90 for a plain statement, 60 for something you are reading into it, 40 for a guess. A medical or a money fact is not yours to write: leave it out.
        A status is what someone is doing or dealing with right now, in plain words, as an onlooker would put it: "on jury duty", "stranded, waiting for a tow", "travelling", "sick". It is never a feeling, a quote or a wish. A feeling goes under mood, which is thrown away, so that it never lands on status.
        An activity/ body is something that repeats, and one meeting of it being called off is not the end of it. "Practice is cancelled tonight" is skipped, the value being when that meeting was going to start, ISO 8601 with the offset in When, worked out from When the way a plan's time is. Never a status on an activity: that word is for the whole series, and only the message saying the series itself is over ("that was the last practice", "we're done for the season") is one.
        Earlier messages are there so the new one reads right: a reply, a pronoun, a mood that carries over. Claim nothing from them.
        A plan the message fixes in time, a day and usually an hour ("dinner Friday at 8", "dentist on the 3rd at 2:30"), is not a claim. Put it beside the claims as "plan":{"title":"<a few words>","starts":"<ISO 8601 with the offset in When>","ends":"<only if said>","location":"<only if said>"}, working the date out from When. A plan with no day, one only hoped for, or one fixed only in the earlier messages is no plan: leave "plan" out.

        Example. Author: ~bus (person/bus). Message: lol did you see the game last night
        {"claims":[]}

        Example. Author: ~bus (person/bus). Message: my new number is 555-0142
        {"claims":[{"subject":"person/bus","attr":"phone","value":"555-0142","conf":90}]}

        Example. Author: ~bus (person/bus). Message: jury duty makes me want to scream
        {"claims":[{"subject":"person/bus","attr":"status","value":"on jury duty","conf":80},{"subject":"person/bus","attr":"mood","value":"frustrated","conf":60}]}

        Example. Author: ~bus (person/bus). Message: ugh, Mondays
        {"claims":[]}

        Example. Bodies include activity/pirates-practice. Author: ~bus (person/bus). When: 2026-09-22T09:12-04:00, a Tuesday. Message: no pirates practice tonight, the rink is flooded
        {"claims":[{"subject":"activity/pirates-practice","attr":"skipped","value":"2026-09-22T18:00:00-04:00","conf":80}]}

        Example. Author: ~bus (person/bus). When: 2026-09-16T14:00-04:00, a Wednesday. Message: dinner at Luigi's Friday at 8 then
        {"claims":[],"plan":{"title":"Dinner at Luigi's","starts":"2026-09-18T20:00:00-04:00","location":"Luigi's"}}
    """.trimIndent()

    fun user(
        bodies: List<KnownBody>,
        author: String,
        authorId: String,
        atIso: String,
        text: String,
        /** What the ship says its own attributes mean, by kind then attr. */
        notes: Map<String, Map<String, String>> = emptyMap(),
        /** The messages before this one in the same conversation, oldest first. */
        context: List<Pair<String, String>> = emptyList(),
    ): String = buildString {
        append("Bodies:\n")
        for (b in bodies.take(60)) {
            append("- ").append(b.id)
            b.name?.let { append(": ").append(it) }
            val extra = (b.aliases + listOfNotNull(b.ship)).filter { it != b.name }.distinct()
            if (extra.isNotEmpty()) append(" (").append(extra.take(6).joinToString(", ")).append(")")
            append('\n')
        }
        // The ship's own wording wins over ours: it is the owner who
        // decides what an attribute of theirs means.
        val said = notes.flatMap { (kind, byAttr) -> byAttr.map { (attr, note) -> "$kind.$attr: $note" } }
        if (said.isNotEmpty()) {
            append("What the attributes mean:\n")
            said.take(12).forEach { append("- ").append(it.take(200)).append('\n') }
        }
        if (context.isNotEmpty()) {
            append("Earlier messages, for reading only, oldest first. Claim nothing from these:\n")
            for ((who, line) in context.takeLast(CONTEXT_MESSAGES)) {
                append("- ").append(who).append(": ").append(line.take(300)).append('\n')
            }
        }
        append("Author: ").append(author).append(" (").append(authorId).append(")\n")
        append("When: ").append(atIso).append('\n')
        append("Message: ").append(text.take(1200)).append('\n')
    }

    /** Ask [model] about one message. Empty on any failure to answer in shape; a plan that stands goes to [onPlan]. */
    suspend fun extract(
        model: LocalModel,
        index: NameIndex,
        bodies: List<KnownBody>,
        text: String,
        author: String,
        atMs: Long,
        ourShip: String,
        attrs: Map<String, List<String>> = emptyMap(),
        notes: Map<String, Map<String, String>> = emptyMap(),
        context: List<Pair<String, String>> = emptyList(),
        onPlan: (Plan) -> Unit = {},
        zone: kotlinx.datetime.TimeZone = kotlinx.datetime.TimeZone.currentSystemDefault(),
    ): List<Noticed> {
        if (text.isBlank() || text.trimEnd().endsWith("?")) return emptyList()
        val authorId = index.authorId(author, ourShip)
        val prompt = user(bodies, author, authorId, whenLine(atMs, zone), text, notes, context)
        val answer = runCatching { model.complete(SYSTEM, prompt, GRAMMAR, MAX_TOKENS) }
            .getOrElse { io.nisfeb.talon.util.Log.w("ModelExtractor", "${model.rung} did not answer: ${it.message}", it); return emptyList() }
        // Only the author and what the message names may be claimed about: a
        // small model otherwise writes what it remembers, not what it read.
        val mentioned = index.find(text).map { it.first.id }.toSet() + authorId + (if (author == ourShip) setOf("person/me") else emptySet())
        planOf(answer, text, atMs)?.let(onPlan)
        return parse(answer, index, author, atMs, ourShip, mentioned, attrs, text, context.map { it.second })
    }

    /** A plan the message fixed in time: a calendar event for the owner to approve. */
    data class Plan(val title: String, val startMs: Long, val endMs: Long?, val location: String?)

    /**
     * The plan in [answer], where it stands: the message names a day or
     * an hour, the title is in its words, the start is ahead of it (a
     * few hours' grace for "tonight") and within the year, an end after
     * the start and within a fortnight of it, and a place only where
     * the message says it. What fails is dropped, never repaired.
     */
    fun planOf(answer: String, text: String, atMs: Long): Plan? {
        val root = runCatching { Json.parseToJsonElement(answer.trim()).jsonObject }.getOrNull() ?: return null
        val p = root["plan"] as? JsonObject ?: return null
        fun str(k: String) = p[k].asStr()?.trim()?.takeIf { it.isNotEmpty() }
        fun ms(k: String) = str(k)?.let(::parseIsoUtc)
        val title = str("title")?.take(120) ?: return null
        if (!FIXES_A_TIME.containsMatchIn(text.lowercase())) return null
        if (!sharesAWord(JsonPrimitive(title), text, min = 3)) return null
        val start = ms("starts") ?: return null
        if (start < atMs - 6 * HOUR_MS || start > atMs + 366L * 24 * HOUR_MS) return null
        val end = ms("ends")?.takeIf { it > start && it <= start + 14L * 24 * HOUR_MS }
        val place = str("location")?.take(200)?.takeIf { text.contains(it, ignoreCase = true) }
        return Plan(title, start, end, place)
    }

    /** The calendar action a plan is filed as, in the schema's payload shape (times ISO 8601 UTC). */
    fun planAction(p: Plan, about: List<String>): JsonObject = buildJsonObject {
        put("kind", "calendar")
        put("title", p.title)
        put("about", kotlinx.serialization.json.JsonArray(about.map { JsonPrimitive(it) }))
        put("payload", buildJsonObject {
            put("title", p.title)
            put("starts", isoUtc(p.startMs))
            p.endMs?.let { put("ends", isoUtc(it)) }
            p.location?.let { put("location", it) }
        })
    }

    /** When, as the model needs it to work a date out: local, with its offset, and the weekday. */
    fun whenLine(atMs: Long, zone: kotlinx.datetime.TimeZone): String {
        val at = kotlinx.datetime.Instant.fromEpochMilliseconds(atMs)
        val local = at.toLocalDateTime(zone)
        val day = local.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
        return "${local.date}T${local.time}${zone.offsetAt(at)}, a $day"
    }

    /** A day, a date or an hour in the words: what a plan fixed in time has. */
    private val FIXES_A_TIME = Regex(
        "\\b(\\d{1,2}(:\\d{2})? ?(am|pm)|\\d{1,2}:\\d{2}|at \\d{1,2}|noon|midnight|tonight|tomorrow|today|" +
            "(mon|tues?|wed(nes)?|thu(rs)?|fri|sat(ur)?|sun)(day)?|" +
            "(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.? \\d{1,2}|\\d{1,2}(st|nd|rd|th)|\\d{1,2}/\\d{1,2})\\b",
    )

    /** The claims in [answer] that pass, each with the body a stranger's claim needs. */
    fun parse(
        answer: String,
        index: NameIndex,
        author: String,
        atMs: Long,
        ourShip: String,
        /** Subjects a claim may have; null for any the ship knows. */
        mentioned: Set<String>? = null,
        /** The ship's attrs per kind; a kind not listed accepts any attr. */
        attrs: Map<String, List<String>> = emptyMap(),
        /** The message, when a value must be found in its words to stand. */
        text: String? = null,
        /** The earlier messages shown, which a claim may have been read from instead. */
        context: List<String> = emptyList(),
    ): List<Noticed> {
        val authorId = index.authorId(author, ourShip)
        val root = runCatching { Json.parseToJsonElement(answer.trim()).jsonObject }.getOrNull() ?: return emptyList()
        val claims = root["claims"]?.let { it as? kotlinx.serialization.json.JsonArray } ?: return emptyList()
        val out = mutableListOf<Noticed>()
        for (c in claims) {
            val o = c as? JsonObject ?: continue
            val said = o["subject"]?.jsonPrimitive?.content?.trim() ?: continue
            // A small model writes the ship or the name as often as the id.
            val subject = when {
                said == authorId || said == "person/me" || index.has(said) -> said
                said == author -> authorId
                else -> index.resolveExact(said)?.id ?: continue
            }
            if (mentioned != null && subject !in mentioned) continue
            val attr = o["attr"]?.jsonPrimitive?.content?.trim()?.lowercase() ?: continue
            if (!ATTR.matches(attr)) continue
            // A medical fact is `health` and a money fact is `income`.
            // Whether this client may write them is the ship's to say,
            // not a constant here: its schema view names them for a key
            // the owner minted to write what it can never read, and for
            // no other. Under any other name they would land in plain
            // sight of every key, so those names go whatever the scope.
            val known = attrs[subject.substringBefore('/')]
            if (attr in SENSITIVE && known?.contains(attr) != true) continue
            if (attr in SENSITIVE_BY_ANOTHER_NAME) continue
            // The prompt offers these so a feeling has somewhere to go
            // that is not status. Nothing comes of them here.
            if (attr in SINK) continue
            if (known != null && attr !in known && attr !in ALWAYS) continue
            val value: JsonElement = when (val v = o["value"]) {
                null -> continue
                is JsonNull -> JsonNull
                is JsonPrimitive -> {
                    if (!v.isString || v.content.isBlank() || v.content.length > 500) continue
                    val str = v.content.trim()
                    // A name written as a string is the body it names.
                    val named = index.resolveExact(str)?.takeIf { it.id != subject }
                    if (named != null) buildJsonObject { put("ref", named.id) } else JsonPrimitive(str)
                }
                is JsonObject -> {
                    val said = v["ref"]?.jsonPrimitive?.content ?: continue
                    val ref = if (index.has(said)) said else index.resolveExact(said)?.id ?: continue
                    if (ref == subject) continue
                    buildJsonObject { put("ref", ref) }
                }
                else -> continue
            }
            // A situation's status is open, closed or cancelled and
            // nothing else: upcoming, under way and over are read off
            // its times. Open goes too, dated later than a close it
            // would reopen, and the calendar writes no status at all.
            if (attr == "status" && subject.startsWith("situation/") &&
                (value as? JsonPrimitive)?.content?.lowercase() !in SITUATION_STATUS
            ) continue
            // An activity's status is the whole series: cancelled says
            // it has stopped for good. "Practice is cancelled tonight"
            // is one occurrence, which since orrery 37 is a `skipped`
            // row carrying that occurrence's start, and never a status.
            // A message that plainly ends the series still ends it.
            if (attr == "status" && subject.startsWith("activity/")) {
                val said = (value as? JsonPrimitive)?.content?.lowercase()
                if (said != "active" && !(said == "cancelled" && text != null && endsTheSeries(text))) continue
            }
            // A value has to come from the words, or it came from the
            // model's memory: the string itself, or the named body's name
            // or an alias, must be in the message. A status is the one
            // attr whose whole point is a paraphrase, so it is judged
            // against the earlier messages instead: one that reads like
            // them and not like this message is a reading of them.
            // A value has to be in the words, except where the whole
            // point of it is that it is not: a status is a paraphrase
            // and a `skipped` is an instant the model worked out from
            // "tonight" and the owner's clock. Neither is ever quoted.
            if (text != null && attr != "status" && attr !in RESOLVED && !grounded(value, text, index)) continue
            if (text != null && attr == "status" && !sharesAWord(value, text) &&
                context.any { sharesAWord(value, it) }
            ) continue
            val conf = (o["conf"]?.jsonPrimitive?.doubleOrNull ?: 50.0).toInt().coerceIn(0, MAX_CONF)
            if (conf < 30) continue
            val until = o["until_hours"]?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0 && it <= 24 * 30 }?.let { atMs + (it * HOUR_MS).toLong() }
            val body = if (subject == authorId && subject != "person/me" && !index.has(subject)) OBody(subject, aliases = listOf(author)) else null
            out += Noticed(subject, attr, value, atMs, until, conf, body)
        }
        // One row per subject and attribute, so a model that says a
        // thing twice does not write it twice — except where the
        // attribute is meant to hold several, and a second cancelled
        // evening has to stand beside the first rather than replace it.
        return out.distinctBy { claimKey(it.subject, it.attr, it.value) }
    }

    /**
     * Whether [text] ends the series rather than one of its meetings.
     *
     * "Practice is cancelled tonight" and "practice is over for the
     * season" both reach the model as a cancellation, and only the
     * second one may retire the activity. So the series is ended by
     * the words, not by the reading of them: something in the message
     * has to say it is not coming back. Anything short of that leaves
     * the activity running, because an activity wrongly retired takes
     * every future occurrence with it and nobody is told.
     */
    internal fun endsTheSeries(text: String): Boolean {
        val t = text.lowercase()
        return ENDINGS.any { it in t }
    }

    /** Said of a series, never of one evening. Kept deliberately short:
     *  a phrase earns its place by being unsayable about tonight. */
    private val ENDINGS = listOf(
        "for the season", "for this season", "rest of the season", "season is over",
        "for the year", "rest of the year", "for the summer", "for the winter",
        "for good", "permanently", "for ever", "forever", "no more",
        "end the series", "ending the series", "series is over", "series is done",
        "last practice", "last session", "last meeting", "last class", "last one",
        "wound up", "winding up", "winding down", "shutting down", "shut down",
        "disbanded", "disbanding", "not coming back", "done for the season",
        "no longer running", "stopped running", "called it off for",
    )

    /** Whether a paraphrase could be of this text: one word of [min] letters or more in common. */
    private fun sharesAWord(value: JsonElement, text: String, min: Int = 4): Boolean {
        val said = (value as? JsonPrimitive)?.content?.lowercase() ?: return true
        val words = WORD.findAll(text.lowercase()).map { it.value }.filter { it.length >= min }.toSet()
        if (words.isEmpty()) return false
        return WORD.findAll(said).any { it.value.length >= min && it.value in words }
    }

    private val WORD = Regex("[a-z0-9']+")

    private fun grounded(value: JsonElement, text: String, index: NameIndex): Boolean = when (value) {
        is JsonNull -> true
        is JsonPrimitive -> text.contains(value.content, ignoreCase = true)
        is JsonObject -> {
            val id = value["ref"]?.jsonPrimitive?.content
            val found = index.find(text).map { it.first.id }
            id != null && id in found
        }
        else -> false
    }

    private val ATTR = Regex("[a-z0-9]([a-z0-9-]{0,46}[a-z0-9])?")
    /** Attrs any kind may carry whatever the schema lists. */
    private val ALWAYS = setOf("status", "location")

    /**
     * Attrs whose value is worked out rather than quoted, and so are
     * not held to being findable in the message. `skipped` is the
     * start of the occurrence that is off, which the model resolves
     * from "tonight" and the owner's clock.
     */
    private val RESOLVED = setOf("skipped")

    /** How many of the messages before this one the model is shown. */
    const val CONTEXT_MESSAGES = 4

    /** All a situation's status may say; the times say whether it is ahead, on or over. */
    private val SITUATION_STATUS = setOf("closed", "cancelled")

    /** Where a feeling goes so that it never lands on status. Never sent. */
    private val SINK = setOf("mood", "feeling", "feelings", "emotion")

    /**
     * The two names the ship keeps from keys (orrery's starter policy).
     * A key minted with `sensitive: write` may observe them, and the
     * ship says so by listing them in the schema it serves that key.
     */
    private val SENSITIVE = setOf("health", "income")

    /** What a model reaches for when it means one of those two. */
    private val SENSITIVE_BY_ANOTHER_NAME = setOf(
        "medical", "diagnosis", "illness", "sickness", "symptom", "symptoms", "medication",
        "meds", "prescription", "treatment", "therapy", "surgery", "condition",
        "salary", "pay", "wage", "wages", "earnings", "debt", "savings", "net-worth", "networth",
    )
}
