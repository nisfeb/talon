package io.nisfeb.talon.calendar

import io.nisfeb.talon.util.nowMs
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** An event as a message: name, when, where, and the note. */
fun eventShareText(name: String, whenLine: String, location: String, note: String, tags: List<String>): String = buildString {
    append("📅 ").append(name.ifBlank { "(untitled)" }).append('\n')
    append(whenLine)
    if (location.isNotBlank()) append('\n').append("Where: ").append(location)
    if (note.isNotBlank()) append('\n').append(note)
    if (tags.isNotEmpty()) append('\n').append(tags.joinToString(" ") { "#$it" })
}

/**
 * An event as iCalendar, the file every calendar imports. A timed one
 * carries its moments in UTC; a whole day carries dates.
 */
fun eventIcs(id: String, name: String, location: String, note: String, startMs: Long, endMs: Long, allDay: Boolean): String {
    fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r", "\\r").replace("\n", "\\n")
    fun stamp(ms: Long): String {
        val t = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.UTC)
        fun p(n: Int) = n.toString().padStart(2, '0')
        return "${t.year}${p(t.monthNumber)}${p(t.dayOfMonth)}T${p(t.hour)}${p(t.minute)}${p(t.second)}Z"
    }
    fun day(ms: Long): String {
        val d = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.UTC).date
        fun p(n: Int) = n.toString().padStart(2, '0')
        return "${d.year}${p(d.monthNumber)}${p(d.dayOfMonth)}"
    }
    // The id goes into the file raw, so nothing that could end its line
    // early -- or smuggle a new one in -- may survive into the UID.
    val safeId = id.filter { it.code in 0x20..0x7e }
    val lines = mutableListOf(
        "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Talon//Calendar//EN", "METHOD:PUBLISH",
        "BEGIN:VEVENT", "UID:$safeId@talon", "DTSTAMP:${stamp(nowMs())}",
    )
    if (allDay) { lines += "DTSTART;VALUE=DATE:${day(startMs)}"; lines += "DTEND;VALUE=DATE:${day(endMs)}" }
    else { lines += "DTSTART:${stamp(startMs)}"; lines += "DTEND:${stamp(endMs)}" }
    lines += "SUMMARY:${esc(name)}"
    if (location.isNotBlank()) lines += "LOCATION:${esc(location)}"
    if (note.isNotBlank()) lines += "DESCRIPTION:${esc(note)}"
    lines += "END:VEVENT"; lines += "END:VCALENDAR"
    return lines.joinToString("\r\n") + "\r\n"
}

/**
 * The message an event goes out as: what, when, where, and the card
 * tag, so everyone who sees it gets Add. An event with no length gets
 * an hour on the card.
 */
fun eventCardMessage(name: String, whenLine: String, location: String, note: String, tags: List<String>, startMs: Long, endMs: Long): String =
    eventShareText(name, whenLine, location, note, tags) + "\n" +
        io.nisfeb.talon.ui.encodeCalTag(startMs, if (endMs > startMs) endMs else startMs + 3_600_000L, name.ifBlank { "Event" })
