package io.nisfeb.talon.orrery

import io.nisfeb.talon.ai.profile
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * A typed decision from TypeSafe's Jev through OpenRouter's decisions
 * route: the state is any JSON, the questions are typed (noul, a
 * probability of yes; choice, one of a set with a probability each), and
 * so is the answer. One pass, under half a second, output free. It
 * stands in front of the analyst and behind it, answering what needs no
 * prose. orrery-utils common/analyze.py is the reference.
 */
interface Decider {
    suspend fun ask(state: JsonObject, questions: JsonObject): Decision
}

/** The answers by question key, and what the call cost. */
data class Decision(val answers: JsonObject, val inputTokens: Long? = null, val costUsd: Double? = null)

/**
 * The decision model's settings, per device. The route is alpha and may
 * move, so it is here and not in the code; so are the model and the
 * gate's threshold. Off until the owner turns it on: every message the
 * reader would read goes to OpenRouter, under zero data retention.
 */
@Serializable
data class DecideSettings(
    /** The decider runs: statuses are checked, and the gate can be tried. */
    val on: Boolean = false,
    /** The gate stands in front of the reader, at [threshold]. Off until the owner has run the check. */
    val gate: Boolean = false,
    val threshold: Double = 0.3,
    val url: String = DECISIONS_URL,
    val model: String = "typesafe/jev-1.13",
    val timeoutMs: Long = 30_000,
    /** Jev chooses the bodies the reader sees, from those it scores at or above [keep]. Off until the owner has run the check. */
    val relevance: Boolean = false,
    val keep: Double = 0.5,
) {
    companion object {
        const val DECISIONS_URL = "https://openrouter.ai/api/alpha/decisions"
    }
}

/**
 * The OpenRouter decisions route. The provider rule is not optional:
 * message text leaves the machine, and zero data retention is the
 * owner's standing requirement.
 */
class OpenRouterDecider(
    private val http: HttpClient,
    private val key: String,
    private val settings: DecideSettings,
) : Decider {
    override suspend fun ask(state: JsonObject, questions: JsonObject): Decision {
        val body = buildJsonObject {
            put("model", settings.model)
            put("state", state)
            put("questions", questions)
            putJsonObject("provider") { put("zdr", true) }
        }
        val resp = http.post(settings.url) {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $key")
            setBody(body.toString())
            timeout { requestTimeoutMillis = settings.timeoutMs }
        }
        val text = resp.bodyAsText()
        if (!resp.status.isSuccess()) error("decision model answered ${resp.status.value}: ${text.take(300)}")
        val o = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrElse { error("decision model answered no JSON: ${text.take(200)}") }
        val answers = o["answers"] as? JsonObject ?: error("decision model answered without answers: ${text.take(300)}")
        val usage = o["usage"] as? JsonObject
        return Decision(
            answers,
            (usage?.get("input_tokens") as? JsonPrimitive)?.longOrNull,
            (usage?.get("cost") as? JsonPrimitive)?.doubleOrNull,
        )
    }
}

/** How many bodies go to the decision model at most: a guard, not a budget. */
const val MAX_KNOWN = 1000

/** One body as the decision model reads it: `id | name | aliases`. */
fun knownLine(b: KnownBody): String =
    b.id + " | " + b.name.orEmpty() + if (b.aliases.isNotEmpty()) " | " + b.aliases.joinToString(", ") else ""

/**
 * The ship's bodies in the order a reader should meet them: those named
 * in the message or the ones before it first, then people, then
 * activities, places and orgs, then situations, then things. Where a
 * list has to be cut, this is what decides what is cut.
 */
fun rankBodies(bodies: List<KnownBody>, index: NameIndex, text: String, earlier: List<String>): List<KnownBody> {
    val named = (listOf(text) + earlier).flatMap { t -> index.find(t).map { it.first.id } }.toSet()
    val rank = mapOf("person" to 1, "activity" to 2, "place" to 2, "org" to 2, "situation" to 3, "thing" to 4)
    return bodies.sortedBy { if (it.id in named) 0 else rank[it.id.substringBefore('/')] ?: 5 }
}

/**
 * Which bodies a message is about, asked of the decision model: one noul
 * question per body, in groups of [GROUP] sent together, each call
 * carrying the whole list of bodies. The list is what makes it work:
 * without it the bodies a message was plainly about scored with the
 * noise, around 0.3; with it they scored 0.8 to 0.96 and the rest 0.06
 * or less (measured 2026-09-19 on 173 bodies).
 */
object Relevance {
    const val GROUP = 40

    private fun about(b: KnownBody): String = (listOfNotNull(b.name) + b.aliases).distinct().joinToString(", ").ifBlank { b.id }

    fun questions(group: List<KnownBody>): JsonObject = buildJsonObject {
        group.forEachIndexed { j, b ->
            putJsonObject("b$j") {
                put("type", "noul")
                put("instructions", "Is the new message about ${b.id} (${about(b)})?")
                putJsonObject("criteria") {
                    put("true", "the message names it or plainly refers to it")
                    put("false", "it does not")
                }
            }
        }
    }

    fun state(text: String, from: String, earlier: List<String>, listing: List<String>): JsonObject = buildJsonObject {
        put("message", text)
        put("from", from)
        putJsonArray("earlier") { earlier.forEach { add(it) } }
        putJsonArray("known_bodies") { listing.forEach { add(it) } }
    }

    /** Each body's score, what the calls cost, and whether every group answered. */
    data class Picked(val scores: Map<String, Double>, val costUsd: Double, val failed: Boolean, val note: String) {
        fun above(keep: Double): List<Pair<String, Double>> = scores.filter { it.value >= keep }.toList().sortedByDescending { it.second }
    }

    suspend fun pick(decider: Decider, text: String, from: String, earlier: List<String>, bodies: List<KnownBody>, atOnce: Int = 6): Picked {
        val all = bodies.take(MAX_KNOWN)
        val st = state(text, from, earlier, all.map(::knownLine))
        val groups = all.chunked(GROUP)
        val permits = kotlinx.coroutines.sync.Semaphore(atOnce)
        val answers = kotlinx.coroutines.coroutineScope {
            groups.map { g ->
                async {
                    permits.acquire()
                    try { runCatching { g to decider.ask(st, questions(g)) } } finally { permits.release() }
                }
            }.map { it.await() }
        }
        val cost = answers.sumOf { r -> r.getOrNull()?.second?.costUsd ?: 0.0 }
        answers.firstOrNull { it.isFailure }?.exceptionOrNull()?.let { e ->
            return Picked(emptyMap(), cost, true, "body picks unavailable, the reader sees the bodies in order: ${e.message?.take(160)}")
        }
        val scores = buildMap {
            for (r in answers) {
                val (g, d) = r.getOrThrow()
                g.forEachIndexed { j, b -> put(b.id, ((d.answers["b$j"] as? JsonObject)?.get("noul") as? JsonPrimitive)?.doubleOrNull ?: 0.0) }
            }
        }
        val tokens = answers.sumOf { r -> r.getOrNull()?.second?.inputTokens ?: 0L }
        return Picked(scores, cost, false, "body picks: ${groups.size} calls, $tokens tokens in, ${dollars(cost)}")
    }

    /**
     * What the reader sees: the bodies scored at or above [keep], and
     * always the sender and the owner, in [ranked] order. When the picks
     * failed, [ranked] as it is: a failed call never narrows the reader.
     */
    fun chosen(ranked: List<KnownBody>, p: Picked, keep: Double, from: String): List<KnownBody> =
        if (p.failed) ranked
        else ranked.filter { (p.scores[it.id] ?: 0.0) >= keep || it.id == from || it.id == "person/me" }
}

/** The decider's switches as the settings screen holds them. */
class DecideControl(
    val settings: kotlinx.coroutines.flow.StateFlow<DecideSettings>,
    val set: (DecideSettings) -> Unit,
)

/**
 * The settings under the one Jev switch: once the owner has saved a
 * profile, it turns the gate, the status check and the body picks on
 * together, at the thresholds kept here; before, the old switches stand.
 */
fun DecideSettings.under(cfg: io.nisfeb.talon.ai.AiSettings.Config?): DecideSettings {
    val p = cfg?.savedProfile ?: return this
    return if (p.jev) copy(on = true, gate = true, relevance = true) else copy(on = false)
}

/** The OpenRouter key Talon already has: the frontier model's, or the private model's when it points there. */
fun openRouterKey(cfg: io.nisfeb.talon.ai.AiSettings.Config): String? = cfg.profile().jevProvider()?.apiKey

/** Dollars to the millionth, never in exponent form: these calls cost fractions of a cent. */
internal fun dollars(d: Double): String {
    val micro = kotlin.math.round(d * 1_000_000).toLong()
    return "$" + (micro / 1_000_000) + "." + (micro % 1_000_000).toString().padStart(6, '0')
}

private fun Decision.usage(): String =
    "${inputTokens ?: "?"} tokens in, ${costUsd?.let(::dollars) ?: "cost not reported"}"

/**
 * The gate: whether a message carries a fact the analyst should read.
 * The question and the state are orrery-utils' analyze.gate, word for
 * word.
 */
object Gate {
    val QUESTION: JsonObject = buildJsonObject {
        putJsonObject("worth_reading") {
            put("type", "noul")
            put("instructions", "Does the new message state a fact worth recording about a person, thing, place, or a plan, that the analyst should read?")
            putJsonObject("criteria") {
                put("true", "it says where someone is, what they are dealing with, what happened, or what will happen, to whom and when")
                put("false", "chatter, greetings, feelings, jokes, a question, or a request that carries no fact about anyone")
            }
        }
    }

    const val RULE = "a status is a circumstance, never a feeling; only facts about people, things, places and plans are recorded"

    /**
     * Every body the key sees goes, in [rankBodies] order: sending all of
     * them measured the same answer time as eighty, at about eight cents
     * more per thousand messages, and a cut at eighty could drop the one
     * person a message is about. [MAX_KNOWN] is only a guard.
     */
    fun state(text: String, from: String, earlier: List<String>, bodies: List<KnownBody>): JsonObject = buildJsonObject {
        put("message", text)
        put("from", from)
        putJsonArray("earlier") { earlier.forEach { add(it) } }
        putJsonArray("known_bodies") { bodies.take(MAX_KNOWN).forEach { add(knownLine(it)) } }
        put("rule", RULE)
    }

    /** What the gate said about one message. [p] is null when it could not answer. */
    data class Result(val p: Double?, val read: Boolean, val costUsd: Double, val note: String)

    /**
     * Ask, and say whether the analyst reads. A gate that cannot answer
     * lets the message through: it saves money and must never lose a
     * fact.
     */
    suspend fun decide(decider: Decider, threshold: Double, text: String, from: String, earlier: List<String>, bodies: List<KnownBody>): Result {
        val d = runCatching { decider.ask(state(text, from, earlier, bodies), QUESTION) }
            .getOrElse { return Result(null, true, 0.0, "gate unavailable, analyst asked: ${it.message?.take(160)}") }
        val p = ((d.answers["worth_reading"] as? JsonObject)?.get("noul") as? JsonPrimitive)?.doubleOrNull ?: 1.0
        val read = p >= threshold
        val pp = (kotlin.math.round(p * 100) / 100).toString()
        return Result(
            p, read, d.costUsd ?: 0.0,
            (if (read) "gate: $pp, read" else "gate: $pp that this carries a fact, below $threshold: not read") + " (" + d.usage() + ")",
        )
    }

    /**
     * The gate over [n] messages for the check: the first alone, so a
     * model that does not answer is said at once rather than after a
     * run of thirty-second waits, then the rest [atOnce] at a time,
     * since one after another was minutes on a phone. The answers come
     * back in the order asked.
     */
    suspend fun askAll(n: Int, atOnce: Int, ask: suspend (Int) -> Result, progress: (done: Int, total: Int) -> Unit): List<Result> {
        if (n == 0) return emptyList()
        val first = ask(0)
        if (first.p == null) error(first.note.substringAfter("analyst asked: ").ifBlank { "The decision model did not answer." })
        val out = arrayOfNulls<Result>(n)
        out[0] = first
        progress(1, n)
        val lock = kotlinx.coroutines.sync.Mutex()
        val permits = kotlinx.coroutines.sync.Semaphore(atOnce)
        var done = 1
        kotlinx.coroutines.coroutineScope {
            for (i in 1 until n) launch {
                permits.acquire()
                try {
                    val r = ask(i)
                    lock.lock()
                    try { out[i] = r; done++; progress(done, n) } finally { lock.unlock() }
                } finally {
                    permits.release()
                }
            }
        }
        return out.map { it!! }
    }

    /** The gate, then the analyst only when it says read. */
    suspend fun <T> around(
        decider: Decider,
        threshold: Double,
        text: String,
        from: String,
        earlier: List<String>,
        bodies: List<KnownBody>,
        log: (String) -> Unit,
        analyst: suspend () -> List<T>,
    ): Pair<Result, List<T>> {
        val r = decide(decider, threshold, text, from, earlier, bodies)
        log(r.note)
        return r to (if (r.read) analyst() else emptyList())
    }
}

/**
 * Rule 8 of orrery-utils docs/writing-a-client.md, held by a model that
 * cannot answer outside the set: each status the analyst proposes for a
 * person is a circumstance, a feeling or neither, and only a
 * circumstance is written. The check never rewrites a value.
 */
object StatusCheck {
    const val SURE = 0.6

    /** Rule 8 of the client guide, verbatim. */
    const val RULE = "`status` on a person is what they are doing or dealing with right now, in plain words: \"on jury duty\", \"stranded, waiting for a tow\", \"travelling\", \"sick\". Never a feeling, a quote or a wish. The schema's `notes` block says so for every attribute that needs saying; read it from the state view and put it in your prompt. Give feelings a sink the client throws away (`mood`), so a small model has somewhere to put \"want to scream\" that is not `status`. Small models follow a worked example better than a rule; carry two."

    private const val ASK = "Is this proposed status for the person a circumstance or a feeling?"

    /** Which rows are asked about: status on a person, nothing else. */
    fun asked(rows: List<Noticed>): List<Int> =
        rows.indices.filter { rows[it].attr == "status" && rows[it].subject.startsWith("person/") }

    private fun valueOf(n: Noticed) = (n.value as? JsonPrimitive)?.contentOrNull ?: n.value.toString()

    /**
     * One question per row, under status_<n>. Each names its own
     * proposal: asked without it, the model cannot tell which of several
     * a question means and answers them all alike (measured 2026-09-19:
     * "on jury duty", "want to scream" and "at the dentist tomorrow" all
     * came back circumstance at 0.7; named, 1.0 circumstance, 1.0
     * feeling, 0.65 neither).
     */
    fun questions(rows: List<Noticed>, asked: List<Int>): JsonObject = buildJsonObject {
        for (n in asked) putJsonObject("status_$n") {
            put("type", "choice")
            put("instructions", "$ASK The proposal is n=$n: \"${valueOf(rows[n])}\".")
            putJsonObject("criteria") {
                put("circumstance", "what the person is doing or dealing with right now, as an observer would put it: on jury duty, stranded waiting for a tow, travelling, sick, home with the kids")
                put("feeling", "an emotion, a mood, a quote or a wish: want to scream, exhausted, so happy, wishes it were friday")
                put("neither", "not a status at all: a plan, a location, an event, a thing")
            }
        }
    }

    fun state(message: String, from: String, rows: List<Noticed>, asked: List<Int>): JsonObject = buildJsonObject {
        put("message", message)
        put("from", from)
        putJsonArray("proposals") {
            asked.forEach { n -> add(buildJsonObject { put("n", n); put("subject", rows[n].subject); put("value", valueOf(rows[n])) }) }
        }
        put("rule", RULE)
    }

    /** What one check did, for the day's tally. */
    data class Tally(val checked: Int = 0, val kept: Int = 0, val feeling: Int = 0, val neither: Int = 0, val uncertain: Int = 0, val costUsd: Double = 0.0)

    /**
     * The rows with every feeling and non-status dropped. A decider that
     * cannot answer keeps them all: the check protects the record from
     * noise and must never lose a real circumstance to a failed call.
     */
    suspend fun filter(decider: Decider, message: String, from: String, rows: List<Noticed>, log: (String) -> Unit): Pair<List<Noticed>, Tally> {
        val asked = asked(rows)
        if (asked.isEmpty()) return rows to Tally()
        val d = runCatching { decider.ask(state(message, from, rows, asked), questions(rows, asked)) }
            .getOrElse {
                log("status check unavailable, ${asked.size} kept: ${it.message?.take(160)}")
                return rows to Tally(checked = asked.size, kept = asked.size)
            }
        log("status check: " + d.usage())
        var t = Tally(checked = asked.size, costUsd = d.costUsd ?: 0.0)
        val drop = mutableSetOf<Int>()
        for (n in asked) {
            val a = d.answers["status_$n"] as? JsonObject
            val choice = (a?.get("choice") as? JsonPrimitive)?.contentOrNull
            val probs = (a?.get("probabilities") as? JsonObject).orEmpty()
                .mapNotNull { (k, v) -> (v as? JsonPrimitive)?.doubleOrNull?.let { k to it } }.toMap()
            val p = choice?.let { probs[it] } ?: 0.0
            val shown = probs.entries.joinToString(", ") { "${it.key} ${(kotlin.math.round(it.value * 100) / 100)}" }
            val v = valueOf(rows[n])
            when {
                choice == null || p < SURE -> { log("status \"$v\": uncertain ($shown), kept"); t = t.copy(kept = t.kept + 1, uncertain = t.uncertain + 1) }
                choice == "circumstance" -> t = t.copy(kept = t.kept + 1)
                choice == "feeling" -> { log("status \"$v\": ${p.pct()} feeling, dropped"); drop += n; t = t.copy(feeling = t.feeling + 1) }
                else -> { log("status \"$v\": ${p.pct()} $choice, dropped"); drop += n; t = t.copy(neither = t.neither + 1) }
            }
        }
        return rows.filterIndexed { i, _ -> i !in drop } to t
    }

    private fun Double.pct() = (kotlin.math.round(this * 100) / 100).toString()
}

/**
 * One day of the decider's work, kept so the owner can see what it
 * saved. The gate's two numbers and the analyst's cost are the whole
 * case for the gate; the status line is the case for the check.
 */
@Serializable
data class DecideDay(
    val read: Int = 0,
    val skipped: Int = 0,
    val gateUsd: Double = 0.0,
    val analystUsd: Double = 0.0,
    val checked: Int = 0,
    val kept: Int = 0,
    val feeling: Int = 0,
    val neither: Int = 0,
    val uncertain: Int = 0,
    val checkUsd: Double = 0.0,
    /** Messages whose bodies Jev chose, the bodies the reader saw across them, and what choosing cost. */
    val picked: Int = 0,
    val pickedBodies: Int = 0,
    val pickUsd: Double = 0.0,
) {
    operator fun plus(o: DecideDay) = DecideDay(
        read + o.read, skipped + o.skipped, gateUsd + o.gateUsd, analystUsd + o.analystUsd,
        checked + o.checked, kept + o.kept, feeling + o.feeling, neither + o.neither, uncertain + o.uncertain, checkUsd + o.checkUsd,
        picked + o.picked, pickedBodies + o.pickedBodies, pickUsd + o.pickUsd,
    )

    operator fun plus(t: StatusCheck.Tally) = copy(
        checked = checked + t.checked, kept = kept + t.kept, feeling = feeling + t.feeling,
        neither = neither + t.neither, uncertain = uncertain + t.uncertain, checkUsd = checkUsd + t.costUsd,
    )

    fun lines(day: String): List<String> = listOf(
        "gate $day: $read read, $skipped skipped, the gate cost ${dollars(gateUsd)}, the analyst ${dollars(analystUsd)}",
        "status check $day: $checked checked, $kept kept, $feeling dropped as feeling, $neither dropped as neither, $uncertain uncertain, ${dollars(checkUsd)}",
    ) + if (picked == 0) emptyList() else listOf(
        "body picks $day: $picked messages, ${pickedBodies / picked} bodies each on average, ${dollars(pickUsd)}",
    )
}
