package io.nisfeb.talon.orrery

import kotlinx.datetime.Instant
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
