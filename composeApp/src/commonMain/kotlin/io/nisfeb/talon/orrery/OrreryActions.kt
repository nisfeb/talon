package io.nisfeb.talon.orrery

import kotlinx.datetime.Instant
import kotlinx.datetime.atStartOfDayIn
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * A message action's payload as the schema shapes it: the channel it
 * goes out on, the person's body, and the words. Where it goes is never
 * read off the body id: [addressOf] asks the person's own attributes.
 */
data class MessageToSend(val via: String, val to: String, val text: String)

/**
 * The channels Talon sends on, rule 14: an Urbit DM, and nothing else.
 * As of orrery 34 the ship sends Telegram through its bot and mail
 * through auspex, on its own executor fiber, the moment the owner
 * approves. Talon claiming those too would be a second sender racing
 * the first.
 */
val TALON_CHANNELS = setOf("chat")

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
 * Where [to] is reached on the channels Talon serves: the person's own
 * ship, for both of them. A DM goes to it, and so does mail, because
 * auspex carries mail between ships and does not bridge to internet
 * email: an address out of the person's `email` could never arrive.
 * Null when the body has no ship, which the executor reports rather
 * than guesses around.
 */
fun addressOf(state: kotlinx.serialization.json.JsonObject, to: String, via: String): String? {
    if (via !in TALON_CHANNELS) return null
    val body = bodyOf(state, to) ?: return null
    val said = Brief.text(body, "ship")?.trim()?.takeIf { it.isNotEmpty() }
        ?: (body["ship"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.trim()
    return said?.let { if (it.startsWith("~")) it else "~$it" }?.takeIf { PATP.matches(it) }
}

/** Why [to] cannot be reached on [via], for the note the owner reads. */
fun noAddress(state: kotlinx.serialization.json.JsonObject, to: String, via: String): String =
    if (via in TALON_CHANNELS) "$to has no ship on record" else "$to is not reachable on $via from here"

private fun bodyOf(state: kotlinx.serialization.json.JsonObject, id: String) =
    Brief.bodies(state).firstOrNull { (it["id"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull == id }

private val PATP = Regex("~[a-z]{3}(-{0,2}[a-z]{3,6})*")

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
private fun instantMs(s: String): Long? = runCatching { Instant.parse(s).toEpochMilliseconds() }.getOrNull()
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
        val why = (a.payload["why"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
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
