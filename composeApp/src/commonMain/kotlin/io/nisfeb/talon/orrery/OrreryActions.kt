package io.nisfeb.talon.orrery

import kotlinx.datetime.Instant
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * What a client-executed action asks for, read out of its payload.
 * The spec fixes little beyond "recipient and text" for a message, so
 * these take the obvious names and give up rather than guess.
 */
data class MessageToSend(val whom: String, val text: String)

data class EventToAdd(val title: String, val startMs: Long, val endMs: Long)

fun OrreryAction.messageToSend(): MessageToSend? {
    if (kind != "message") return null
    val whom = str("recipient") ?: str("to") ?: str("whom") ?: about.firstOrNull { it.startsWith("person/") && it != "person/me" }?.let { "~" + it.removePrefix("person/") }
    val text = str("text") ?: str("body") ?: return null
    if (whom == null || !whom.startsWith("~") || text.isBlank()) return null
    return MessageToSend(whom, text)
}

fun OrreryAction.eventToAdd(): EventToAdd? {
    if (kind != "calendar") return null
    val start = (str("start") ?: str("when") ?: due)?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() } ?: return null
    val end = str("end")?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }?.takeIf { it > start } ?: (start + 60L * 60 * 1000)
    val name = (str("title") ?: title).trim()
    if (name.isEmpty()) return null
    return EventToAdd(name, start, end)
}

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
