package io.nisfeb.talon.ui

import io.nisfeb.talon.util.formatTime12
import io.nisfeb.talon.util.formatWeekdayMonthDay
import io.nisfeb.talon.util.nowMs
import io.nisfeb.talon.util.percentDecodeComponent
import io.nisfeb.talon.util.percentEncodeComponent
import kotlinx.datetime.DateTimeUnit
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Forgiving natural-language calendar parsing for the `/cal` command.
 * Input is arbitrary user text like "thurs 2-3p Meet John"; output is
 * (startMs, endMs, title) as epoch-millis. Mirrors yap/ui/src/util/cal.ts
 * — see that file for the source commentary.
 */
sealed interface CalParseResult {
    data class Ok(
        val startMs: Long,
        val endMs: Long,
        val title: String,
        val defaultDate: Boolean,
        val defaultDuration: Boolean,
    ) : CalParseResult

    data class Err(val error: String) : CalParseResult
}

private val WEEKDAYS: Map<String, Int> = mapOf(
    "sun" to 0, "sunday" to 0,
    "mon" to 1, "monday" to 1,
    "tue" to 2, "tues" to 2, "tuesday" to 2,
    "wed" to 3, "weds" to 3, "wednesday" to 3,
    "thu" to 4, "thur" to 4, "thurs" to 4, "thursday" to 4,
    "fri" to 5, "friday" to 5,
    "sat" to 6, "saturday" to 6,
)

private val NOISE: Set<String> = setOf("at", "on", "from", "to", "by", "around", "about")

private const val DEFAULT_DURATION_MS: Long = 60L * 60L * 1000L

/** yap's day-of-week scheme: Sunday=0 … Saturday=6. */
private fun dow0(d: LocalDate): Int = d.dayOfWeek.isoDayNumber % 7

private fun normalizeAmpm(x: String?): String? {
    if (x == null) return null
    val c = x.lowercase()
    if (c.startsWith("a")) return "am"
    if (c.startsWith("p")) return "pm"
    return null
}

private fun applyAmpm(h: Int, ap: String?): Int {
    if (ap == "pm" && h < 12) return h + 12
    if (ap == "am" && h == 12) return 0
    return h
}

private fun parseDateToken(tok: String, today: LocalDate): LocalDate? {
    val t = tok.lowercase().trimEnd('.', ',')
    if (t == "today" || t == "tonight") return today
    if (t == "tomorrow" || t == "tmrw" || t == "tmw") return today.plus(1, DateTimeUnit.DAY)
    WEEKDAYS[t]?.let { wd ->
        val diff = (wd - dow0(today) + 7) % 7
        return today.plus(diff, DateTimeUnit.DAY)
    }
    // ISO YYYY-MM-DD
    val iso = Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$").find(t)
    if (iso != null) {
        val (y, m, d) = iso.destructured
        return runCatching { LocalDate(y.toInt(), m.toInt(), d.toInt()) }.getOrNull()
    }
    // US-ish M/D[/YY|YYYY]
    val mdy = Regex("^(\\d{1,2})/(\\d{1,2})(?:/(\\d{2}|\\d{4}))?$").find(t)
    if (mdy != null) {
        val m = mdy.groupValues[1].toInt()
        val d = mdy.groupValues[2].toInt()
        val yraw = mdy.groupValues[3].ifEmpty { null }
        var y = today.year
        if (yraw != null) {
            y = yraw.toInt()
            if (y < 100) y += 2000
        }
        if (m in 1..12 && d in 1..31) {
            return runCatching { LocalDate(y, m, d) }.getOrNull()
        }
    }
    return null
}

internal data class HM(val h: Int, val m: Int)
internal data class TimeRange(val start: HM, val end: HM?)

private val RANGE_RE =
    Regex("^(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a|p)?\\s*[-–—]\\s*(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a|p)?$", RegexOption.IGNORE_CASE)
private val SINGLE_RE =
    Regex("^(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a|p)?$", RegexOption.IGNORE_CASE)

private fun clampHM(h: Int, m: Int): HM? =
    if (h !in 0..23 || m !in 0..59) null else HM(h, m)

internal fun parseTimeToken(tok: String): TimeRange? {
    val t = tok.lowercase().trimEnd('.', ',')
    RANGE_RE.find(t)?.let { r ->
        var h1 = r.groupValues[1].toInt()
        val m1 = r.groupValues[2].ifEmpty { "0" }.toInt()
        var h2 = r.groupValues[4].toInt()
        val m2 = r.groupValues[5].ifEmpty { "0" }.toInt()
        var ap1 = normalizeAmpm(r.groupValues[3].ifEmpty { null })
        var ap2 = normalizeAmpm(r.groupValues[6].ifEmpty { null })
        if (ap1 != null && ap2 == null) ap2 = ap1
        if (ap2 != null && ap1 == null) ap1 = ap2
        h1 = applyAmpm(h1, ap1)
        h2 = applyAmpm(h2, ap2)
        if (ap1 == null && ap2 == null && h1 > h2) h2 += 12
        val s = clampHM(h1, m1) ?: return null
        val e = clampHM(h2, m2) ?: return null
        return TimeRange(s, e)
    }
    SINGLE_RE.find(t)?.let { s ->
        var h = s.groupValues[1].toInt()
        val m = s.groupValues[2].ifEmpty { "0" }.toInt()
        val ap = normalizeAmpm(s.groupValues[3].ifEmpty { null })
        h = applyAmpm(h, ap)
        val hasMinutes = s.groupValues[2].isNotEmpty()
        if (ap == null && !hasMinutes && h in 1..7) h += 12
        val hm = clampHM(h, m) ?: return null
        return TimeRange(hm, null)
    }
    return null
}

fun parseCalText(raw: String, nowMs: Long = nowMs()): CalParseResult {
    val input = raw.trim()
    if (input.isEmpty()) return CalParseResult.Err("missing event details")

    val zone = TimeZone.currentSystemDefault()
    val nowLdt = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone)
    val today = nowLdt.date

    val tokens = input.split(Regex("\\s+"))
    var dateTok: LocalDate? = null
    var timeTok: TimeRange? = null
    val titleToks = mutableListOf<String>()

    for (tok in tokens) {
        if (tok.lowercase() in NOISE) continue
        if (dateTok == null) {
            val d = parseDateToken(tok, today)
            if (d != null) { dateTok = d; continue }
        }
        if (timeTok == null) {
            val t = parseTimeToken(tok)
            if (t != null) { timeTok = t; continue }
        }
        titleToks.add(tok)
    }

    val usedDefaultDate = dateTok == null
    val usedDefaultDur = timeTok?.end == null
    var date = dateTok ?: today
    val range = timeTok ?: TimeRange(HM((nowLdt.hour + 1) % 24, 0), null)

    fun msAt(d: LocalDate, hm: HM): Long =
        d.atTime(hm.h, hm.m).toInstant(zone).toEpochMilliseconds()

    var startMs = msAt(date, range.start)
    if (!usedDefaultDate && startMs < nowMs) {
        val lowered = input.lowercase()
        val weekdayUsed = WEEKDAYS.keys.any { lowered.contains(it) }
        if (weekdayUsed) {
            date = date.plus(7, DateTimeUnit.DAY)
            startMs = msAt(date, range.start)
        }
    }

    val te = range.end
    val endMs = if (te != null) {
        val e = msAt(date, te)
        if (e <= startMs) msAt(date.plus(1, DateTimeUnit.DAY), te) else e
    } else {
        startMs + DEFAULT_DURATION_MS
    }

    val title = titleToks.joinToString(" ").trim().ifEmpty { "Event" }

    return CalParseResult.Ok(
        startMs = startMs,
        endMs = endMs,
        title = title,
        defaultDate = usedDefaultDate,
        defaultDuration = usedDefaultDur,
    )
}

/** Human summary line for the chat body, e.g. "Thu, Apr 24 · 2:00 PM – 3:00 PM". */
fun formatCalSummary(startMs: Long, endMs: Long): String {
    val day = formatWeekdayMonthDay(startMs)
    return "$day · ${formatTime12(startMs)} – ${formatTime12(endMs)}"
}

/** Machine-readable tag appended to the chat body. */
fun encodeCalTag(startMs: Long, endMs: Long, title: String): String =
    "[cal|${formatIsoUtc(startMs)}|${formatIsoUtc(endMs)}|${percentEncodeComponent(title)}]"

val CAL_TAG_RE: Regex = Regex("\\[cal\\|([^|]+)\\|([^|]+)\\|([^\\]\\n]*)\\]")

data class DecodedCal(val startMs: Long, val endMs: Long, val title: String)

fun decodeCalTag(text: String): DecodedCal? {
    val m = CAL_TAG_RE.find(text) ?: return null
    val start = parseIsoUtc(m.groupValues[1]) ?: return null
    val end = parseIsoUtc(m.groupValues[2]) ?: return null
    val title = percentDecodeComponent(m.groupValues[3])
        ?.takeIf { it.isNotBlank() } ?: "Event"
    return DecodedCal(start, end, title)
}
