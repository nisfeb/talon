package io.nisfeb.talon.orrery

import kotlin.time.Instant
import kotlinx.datetime.atStartOfDayIn
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import io.nisfeb.talon.ui.parseIsoUtc
import io.nisfeb.talon.urbit.asText

/**
 * A message action's payload as the schema shapes it: the channel it
 * goes out on, the person's body, and the words. The ship sends it on
 * every channel, chat included, the moment the owner approves.
 */
data class MessageToSend(val via: String, val to: String, val text: String)

/** A calendar action's event: [endMs] and [location] only where the payload says; [bareDate] when it gave a day and no time. */
data class EventToAdd(val title: String, val startMs: Long, val endMs: Long?, val location: String? = null, val bareDate: Boolean = false)

fun OrreryAction.messageToSend(): MessageToSend? {
    if (kind != "message") return null
    val via = str("via")?.lowercase() ?: return null
    val to = str("to")?.lowercase() ?: return null
    val text = str("text")?.trim() ?: return null
    return MessageToSend(via, to, text)
}

/**
 * The event a calendar action asks for, in the schema's payload shape:
 * title, starts and, when known, ends and location. The older names an
 * earlier analyst wrote (start, end, when) are still read.
 */
fun OrreryAction.eventToAdd(): EventToAdd? {
    if (kind != "calendar") return null
    val said = str("starts") ?: str("start") ?: str("when") ?: due ?: return null
    val start = instantMs(said) ?: return null
    val end = (str("ends") ?: str("end"))?.let(::instantMs)?.takeIf { it > start }
    val name = (str("title") ?: title).trim()
    if (name.isEmpty()) return null
    return EventToAdd(name, start, end, str("location")?.trim(), bareDate = 'T' !in said)
}

/** An ISO 8601 instant, or a bare date read as that day at midnight UTC. */
private fun instantMs(s: String): Long? = parseIsoUtc(s)
    ?: runCatching { kotlinx.datetime.LocalDate.parse(s).atStartOfDayIn(kotlinx.datetime.TimeZone.UTC).toEpochMilliseconds() }.getOrNull()

private fun OrreryAction.str(k: String): String? =
    payload[k]?.let { v -> runCatching { v.jsonPrimitive.content }.getOrNull() }?.takeIf { it.isNotBlank() }

/**
 * What the ship carries out itself once approved: an event, a message,
 * a change. Marking one done here would skip the doing, and Done on a
 * proposal of one would approve it, which sends it.
 */
val OrreryAction.shipCarriesOut: Boolean get() = eventToAdd() != null || messageToSend() != null || shipChange() != null

/** The orrery that keeps a priority and a snooze on an action, and nags about a late one. */
const val MARKS_VERSION = 110

/** Orrery's evening review, its due route and review_at (orrery 130): gates every way into the review. */
const val EVENING_VERSION = 130

/** How long Snooze 1 h holds the nags, as orrery's own page does. */
const val SNOOZE_MINUTES = 60

/** Marked high (orrery 110): nagged about once its due has passed. */
val OrreryAction.high: Boolean get() = str("priority") == "high"

/** Open as the mark routes take it: the ship refuses one claimed. */
val OrreryAction.markable: Boolean get() = status == "proposed" || status == "approved"

fun OrreryAction.dueMs(): Long? = due?.let(::instantMs)

/**
 * Late as orrery's +late-high has it: open, high, its due passed, and
 * no snooze running. The ship pushes about each one every 15 minutes.
 */
fun OrreryAction.lateAt(nowMs: Long): Boolean {
    val due = dueMs() ?: return false
    val snoozed = str("snooze_until")?.let(::instantMs) ?: 0L
    return markable && high && due < nowMs && snoozed <= nowMs
}

/** The Late section: the late ones, newest due first. */
fun lateActions(actions: List<OrreryAction>, nowMs: Long): List<OrreryAction> =
    actions.filter { it.lateAt(nowMs) }.sortedByDescending { it.dueMs() }

/** How late, to the minute, as orrery's page says it: "40 min", "2 h", "2 h 10 min". */
fun lateSpan(ms: Long): String {
    val m = (ms + 30_000) / 60_000
    return if (m < 60) "$m min" else "${m / 60} h" + (if (m % 60 > 0) " ${m % 60} min" else "")
}

/** A late one's line on the Late section: "due Sat 18 Oct 19:05, 2 h 10 min late". */
fun lateLine(a: OrreryAction, nowMs: Long, zone: kotlinx.datetime.TimeZone, twentyFourHour: Boolean): String {
    val due = a.dueMs() ?: return ""
    return "due ${a.due?.let { OrreryText.dueText(it, zone, twentyFourHour) }}, ${lateSpan(nowMs - due)} late"
}

/** [this] with one payload field as the ship would hold it: "" takes it off. */
fun OrreryAction.marked(field: String, value: String): OrreryAction =
    copy(payload = kotlinx.serialization.json.JsonObject(if (value.isEmpty()) payload - field else payload + (field to kotlinx.serialization.json.JsonPrimitive(value))))

/** Whether the ship's list shows a mark: the field as sent, or the action gone from it. */
internal fun markShown(there: OrreryAction?, field: String, value: String): Boolean =
    there == null || there.str(field).orEmpty() == value

/** A late push's buttons (Android), in order, in the words of orrery's Late card, and what a failure calls each. */
enum class LateMove(val label: String, val what: String) { DONE("Done", "Done"), SNOOZE("Snooze 1 h", "Snooze") }

/** Why [what] on an action did not go through, in a line: on the Actions page, and in place of the push it was tapped on. */
fun markProblemLine(what: String, e: Throwable): String = "$what did not go through: " + when (e) {
    is OrreryError.Unreachable -> "your ship did not answer. Try it again."
    is OrreryError.Refused -> (io.nisfeb.talon.util.readableReason(e.reason) ?: "Orrery gave no reason") + "."
    else -> (io.nisfeb.talon.util.readableReason(e.message) ?: "something went wrong") + "."
}

/**
 * [move] on action [id] from its late push, as the owner, with no list on
 * hand. Done reads the action first: one no longer open is done already,
 * one the ship carries out is left to the app, since approving it would
 * send it, and a proposal is approved before it is done ([OrreryApi.finish]).
 * Null when it went through, else the line the push is replaced with.
 */
suspend fun OrreryApi.lateMove(id: String, move: LateMove): String? = try {
    when (move) {
        LateMove.SNOOZE -> snooze(null, id, SNOOZE_MINUTES)
        LateMove.DONE -> {
            val a = actions(null).firstOrNull { it.id == id }
            if (a != null && a.shipCarriesOut) return "Open Talon to answer this one: the ship carries it out."
            if (a != null) finish(null, id, proposed = a.status == "proposed")
        }
    }
    null
} catch (c: kotlinx.coroutines.CancellationException) {
    throw c
} catch (e: Throwable) {
    markProblemLine(move.what, e)
}

/** One notification's worth of proposals. [id] is the action's, or blank for the "and more" line. */
data class ActionNotification(val id: String, val title: String, val body: String)

/** What to raise, what to take back, and what to remember, after a read of the open actions. */
data class ActionNews(val raise: List<ActionNotification>, val clear: Set<String>, val seen: Set<String>)

/**
 * The proposals new since the last read, which need the owner's answer;
 * an approved action has had it. Those answered since, here or anywhere,
 * are taken back. The first read of a session only sets the baseline,
 * the way mail does, so opening the app never replays what has waited
 * for days: the New panel shows those.
 */
fun diffActionNotifications(open: List<OrreryAction>, lastSeen: Set<String>?, cap: Int = 3): ActionNews {
    val proposals = open.filter { it.status == "proposed" }
    val seen = proposals.map { it.id }.toSet()
    if (lastSeen == null) return ActionNews(emptyList(), emptySet(), seen)
    val fresh = proposals.filter { it.id !in lastSeen }
    val raise = fresh.take(cap).map { a ->
        val why = a.payload["why"].asText()?.takeIf { it.isNotBlank() }
        ActionNotification(
            a.id,
            a.title.ifBlank { a.kind },
            listOfNotNull("${a.kind}, proposed by ${a.by.ifBlank { "the analyst" }}", why).joinToString(". "),
        )
    } + if (fresh.size > cap) listOf(ActionNotification("", "and ${fresh.size - cap} more waiting for you", "")) else emptyList()
    return ActionNews(raise, lastSeen - seen, seen)
}

/** The kinds the ship's writer carries out itself once approved (orrery 60): nothing for Talon to do. */
val SHIP_APPLIES = setOf("correct", "fact", "merge", "preference")

/**
 * The change one of [SHIP_APPLIES] asks for, in a line: what is struck,
 * stated, folded or kept. A value is a string, a number, a flag or a
 * `{"ref": "kind/slug"}` to another body. Null for any other kind.
 */
fun OrreryAction.shipChange(): String? {
    val value = (payload["value"] as? kotlinx.serialization.json.JsonObject)?.get("ref").asText() ?: payload["value"].asText()
    return when (kind) {
        "correct" -> "Not true: ${str("subject")}'s ${str("attr")} is $value"
        "fact" -> "True: ${str("subject")}'s ${str("attr")} is $value"
        "merge" -> "${str("from")} and ${str("into")} are one, kept as ${str("into")}"
        "preference" -> "From now on: ${str("text")}"
        else -> null
    }
}

/** What the ship could not do in the last week, newest first: the last section of Actions. */
fun recentFailures(failed: List<OrreryAction>, nowMs: Long): List<OrreryAction> =
    failed.filter { (it.movedMs ?: 0L) > nowMs - FAILURES_SHOWN_MS }.sortedByDescending { it.movedMs }.take(20)

const val FAILURES_SHOWN_MS = 7L * 24 * 60 * 60 * 1000

/** An approved action the ship could not carry out, as a notification. */
fun failureNotification(a: OrreryAction) = ActionNotification(
    "failed:${a.id}",
    "Did not go through: ${a.title.ifBlank { a.kind }}",
    a.note.ifBlank { "The ship gave no reason." },
)

/**
 * Orrery's change beacon, read a line at a time off its event stream:
 * [feed] answers the revision each time an event carrying one ends. The
 * first event on a connection is named "old /rev" and carries the
 * revision as it stands, so a change missed while disconnected still
 * shows as a difference.
 */
class BeaconReader {
    private var event = ""
    private var data: String? = null

    fun feed(line: String): String? {
        when {
            line.startsWith("event:") -> event = line.substringAfter(':').trim()
            line.startsWith("data:") -> data = line.substringAfter(':').trim()
            line.isEmpty() -> {
                val rev = data?.takeIf { event.endsWith("/rev") && it.isNotEmpty() }
                event = ""
                data = null
                return rev
            }
        }
        return null
    }
}
