package io.nisfeb.talon.ui

import java.net.URLEncoder
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Forgiving natural-language calendar parsing for the `/cal` command.
 * Input is arbitrary user text like "thurs 2-3p Meet John"; output is
 * (start, end, title). Mirrors yap/ui/src/util/cal.ts — see that file
 * for the source commentary.
 */
sealed interface CalParseResult {
    data class Ok(
        val start: Date,
        val end: Date,
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

private fun startOfDay(d: Date): Date {
    val c = Calendar.getInstance()
    c.time = d
    c.set(Calendar.HOUR_OF_DAY, 0)
    c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    return c.time
}

private fun addDays(d: Date, n: Int): Date {
    val c = Calendar.getInstance()
    c.time = d
    c.add(Calendar.DAY_OF_YEAR, n)
    return c.time
}

private fun calendarDayOfWeek(d: Date): Int {
    // Calendar.SUNDAY = 1 … SATURDAY = 7; yap uses 0..6 with Sunday=0.
    val c = Calendar.getInstance()
    c.time = d
    return c.get(Calendar.DAY_OF_WEEK) - 1
}

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

private fun parseDateToken(tok: String, now: Date): Date? {
    val t = tok.lowercase().trimEnd('.', ',')
    if (t == "today" || t == "tonight") return startOfDay(now)
    if (t == "tomorrow" || t == "tmrw" || t == "tmw") return addDays(startOfDay(now), 1)
    WEEKDAYS[t]?.let { wd ->
        val d0 = startOfDay(now)
        val diff = (wd - calendarDayOfWeek(d0) + 7) % 7
        return addDays(d0, diff)
    }
    // ISO YYYY-MM-DD
    val iso = Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$").find(t)
    if (iso != null) {
        val (y, m, d) = iso.destructured
        val c = Calendar.getInstance()
        c.clear()
        c.set(y.toInt(), m.toInt() - 1, d.toInt())
        return c.time
    }
    // US-ish M/D[/YY|YYYY]
    val mdy = Regex("^(\\d{1,2})/(\\d{1,2})(?:/(\\d{2}|\\d{4}))?$").find(t)
    if (mdy != null) {
        val m = mdy.groupValues[1].toInt()
        val d = mdy.groupValues[2].toInt()
        val yraw = mdy.groupValues[3].ifEmpty { null }
        var y = Calendar.getInstance().apply { time = now }.get(Calendar.YEAR)
        if (yraw != null) {
            y = yraw.toInt()
            if (y < 100) y += 2000
        }
        if (m in 1..12 && d in 1..31) {
            val c = Calendar.getInstance()
            c.clear()
            c.set(y, m - 1, d)
            return c.time
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

fun parseCalText(raw: String, now: Date = Date()): CalParseResult {
    val input = raw.trim()
    if (input.isEmpty()) return CalParseResult.Err("missing event details")

    val tokens = input.split(Regex("\\s+"))
    var dateTok: Date? = null
    var timeTok: TimeRange? = null
    val titleToks = mutableListOf<String>()

    for (tok in tokens) {
        if (tok.lowercase() in NOISE) continue
        if (dateTok == null) {
            val d = parseDateToken(tok, now)
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
    if (dateTok == null) dateTok = startOfDay(now)
    if (timeTok == null) {
        val nextHour = (Calendar.getInstance().apply { time = now }
            .get(Calendar.HOUR_OF_DAY) + 1) % 24
        timeTok = TimeRange(HM(nextHour, 0), null)
    }

    val start = Calendar.getInstance().apply {
        time = dateTok!!
        set(Calendar.HOUR_OF_DAY, timeTok!!.start.h)
        set(Calendar.MINUTE, timeTok!!.start.m)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    if (!usedDefaultDate && start.time.before(now)) {
        val lowered = input.lowercase()
        val weekdayUsed = WEEKDAYS.keys.any { lowered.contains(it) }
        if (weekdayUsed) start.add(Calendar.DAY_OF_YEAR, 7)
    }
    val end = Calendar.getInstance().apply { time = start.time }
    val te = timeTok!!.end
    if (te != null) {
        end.set(Calendar.HOUR_OF_DAY, te.h)
        end.set(Calendar.MINUTE, te.m)
        end.set(Calendar.SECOND, 0)
        end.set(Calendar.MILLISECOND, 0)
        if (!end.time.after(start.time)) end.add(Calendar.DAY_OF_YEAR, 1)
    } else {
        end.timeInMillis = start.timeInMillis + DEFAULT_DURATION_MS
    }

    val title = titleToks.joinToString(" ").trim().ifEmpty { "Event" }

    return CalParseResult.Ok(
        start = start.time,
        end = end.time,
        title = title,
        defaultDate = usedDefaultDate,
        defaultDuration = usedDefaultDur,
    )
}

/** Human summary line for the chat body, e.g. "Thu, Apr 24 · 2:00 PM – 3:00 PM". */
fun formatCalSummary(start: Date, end: Date): String {
    val day = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(start)
    val startT = SimpleDateFormat("h:mm a", Locale.getDefault()).format(start)
    val endT = SimpleDateFormat("h:mm a", Locale.getDefault()).format(end)
    return "$day · $startT – $endT"
}

/** Machine-readable tag appended to the chat body. */
fun encodeCalTag(start: Date, end: Date, title: String): String {
    val iso = { d: Date -> SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }.format(d) }
    val enc = URLEncoder.encode(title, "UTF-8")
    return "[cal|${iso(start)}|${iso(end)}|$enc]"
}

val CAL_TAG_RE: Regex = Regex("\\[cal\\|([^|]+)\\|([^|]+)\\|([^\\]\\n]*)\\]")

data class DecodedCal(val start: Date, val end: Date, val title: String)

fun decodeCalTag(text: String): DecodedCal? {
    val m = CAL_TAG_RE.find(text) ?: return null
    val start = parseIsoUtc(m.groupValues[1]) ?: return null
    val end = parseIsoUtc(m.groupValues[2]) ?: return null
    val title = runCatching { java.net.URLDecoder.decode(m.groupValues[3], "UTF-8") }
        .getOrNull()?.takeIf { it.isNotBlank() } ?: "Event"
    return DecodedCal(start, end, title)
}
