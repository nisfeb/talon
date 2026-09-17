package io.nisfeb.talon.orrery

import kotlinx.datetime.Instant
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
