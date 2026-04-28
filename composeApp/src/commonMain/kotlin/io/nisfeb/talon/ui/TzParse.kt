package io.nisfeb.talon.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * `/tz <time> [zone]` — parses a time in the sender's (or a
 * user-supplied) timezone and produces an absolute ISO timestamp the
 * other side can re-render in their own zone.
 *
 * Smarter than yap's: the zone arg is optional. When omitted we assume
 * the system default.
 */

/** Result of parsing a `/tz` input. */
sealed interface TzParseResult {
    data class Ok(
        val instant: Date,
        /** The zone the sender typed in (or system default if omitted). */
        val sourceZone: TimeZone,
        /** Short label ("EDT", "PDT", or the system default's display name). */
        val sourceLabel: String,
    ) : TzParseResult

    data class Err(val error: String) : TzParseResult
}

private val ZONE_ALIASES: Map<String, String> = mapOf(
    // Eastern
    "eastern" to "America/New_York",
    "et" to "America/New_York",
    "est" to "America/New_York",
    "edt" to "America/New_York",
    "ny" to "America/New_York",
    "nyc" to "America/New_York",
    // Central
    "central" to "America/Chicago",
    "ct" to "America/Chicago",
    "cst" to "America/Chicago",
    "cdt" to "America/Chicago",
    "chi" to "America/Chicago",
    "chicago" to "America/Chicago",
    // Mountain
    "mountain" to "America/Denver",
    "mt" to "America/Denver",
    "mst" to "America/Denver",
    "mdt" to "America/Denver",
    "denver" to "America/Denver",
    // Pacific
    "pacific" to "America/Los_Angeles",
    "pt" to "America/Los_Angeles",
    "pst" to "America/Los_Angeles",
    "pdt" to "America/Los_Angeles",
    "la" to "America/Los_Angeles",
    "sf" to "America/Los_Angeles",
    // UK / UTC
    "utc" to "UTC",
    "gmt" to "UTC",
    "z" to "UTC",
    "london" to "Europe/London",
    "bst" to "Europe/London",
    // Central Europe
    "cet" to "Europe/Berlin",
    "cest" to "Europe/Berlin",
    "berlin" to "Europe/Berlin",
    // Misc
    "tokyo" to "Asia/Tokyo",
    "jst" to "Asia/Tokyo",
    "sydney" to "Australia/Sydney",
)

/**
 * Resolve a user-typed zone token ("eastern", "PDT", "America/New_York")
 * to a TimeZone. Unknown tokens return null so the caller can error.
 */
fun resolveZoneToken(raw: String): TimeZone? {
    val t = raw.trim()
    if (t.isEmpty()) return null
    ZONE_ALIASES[t.lowercase()]?.let { return TimeZone.getTimeZone(it) }
    val direct = TimeZone.getTimeZone(t)
    // getTimeZone returns GMT for unknown IDs; guard against that.
    if (direct.id.equals(t, ignoreCase = true)) return direct
    return null
}

/**
 * Short label for a TimeZone. Uses the IANA short name (EDT/PDT etc.)
 * from ICU; falls back to the 3-letter `getDisplayName(short)`.
 */
fun zoneShortLabel(zone: TimeZone, now: Date = Date()): String {
    return zone.getDisplayName(
        zone.inDaylightTime(now),
        TimeZone.SHORT,
        Locale.getDefault(),
    )
}

/**
 * Parse a `/tz` invocation. Accepts "3p", "3pm", "3:30p", "15:00"
 * optionally followed by a zone token. Anchored to "today"; if the
 * resulting instant is already in the past we bump it to tomorrow.
 */
fun parseTzInput(rawArgs: String, now: Date = Date()): TzParseResult {
    val args = rawArgs.trim()
    if (args.isEmpty()) {
        return TzParseResult.Err(
            "give a time, e.g. \"/tz 3p\" or \"/tz 3p pacific\""
        )
    }
    // Split on whitespace — time is always the first token, remainder
    // is an optional zone phrase (may contain multiple words like
    // "america/new_york").
    val parts = args.split(Regex("\\s+"), limit = 2)
    val timeTok = parts[0]
    val zoneTok = if (parts.size > 1) parts[1] else ""

    val time = parseTimeToken(timeTok)
        ?: return TzParseResult.Err(
            "couldn't parse time \"$timeTok\" — try 3p, 3:30pm, 15:00"
        )

    val zone = if (zoneTok.isEmpty()) TimeZone.getDefault()
    else resolveZoneToken(zoneTok)
        ?: return TzParseResult.Err(
            "unknown zone \"$zoneTok\" — try pacific / eastern / UTC / or an IANA id"
        )

    // Anchor to today in the source zone, then bump to tomorrow if
    // the resulting instant is in the past.
    val cal = Calendar.getInstance(zone)
    cal.time = now
    cal.set(Calendar.HOUR_OF_DAY, time.start.h)
    cal.set(Calendar.MINUTE, time.start.m)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    if (cal.time.before(now)) cal.add(Calendar.DAY_OF_YEAR, 1)

    val label = zoneShortLabel(zone, cal.time)
    return TzParseResult.Ok(cal.time, zone, label)
}

/** ISO-8601 with millisecond precision in UTC. */
fun formatIsoUtc(d: Date): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(d)

/** Parse the ISO string we encoded. Lenient — returns null on bad input. */
fun parseIsoUtc(s: String): Date? =
    runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse(s)
    }.getOrNull()

/**
 * Render one viewer-side row: "3:00 PM Eastern (Mon)". Same-day is
 * implicit (no suffix); other days get a short weekday tag.
 */
fun formatInZone(d: Date, zone: TimeZone, now: Date = Date()): String {
    val fmt = SimpleDateFormat("h:mm a", Locale.getDefault()).apply { timeZone = zone }
    val time = fmt.format(d)
    val today = Calendar.getInstance(zone).apply { this.time = now }
    val target = Calendar.getInstance(zone).apply { this.time = d }
    if (today.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
        today.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
    ) return time
    val wd = SimpleDateFormat("EEE", Locale.getDefault()).apply { timeZone = zone }
    return "$time (${wd.format(d)})"
}

/** Machine-readable tag the decoder recognizes. */
fun encodeTzTag(instantIso: String, sourceLabel: String): String =
    "[tz|$instantIso|$sourceLabel]"

val TZ_TAG_RE: Regex = Regex("\\[tz\\|([^|\\]]+)\\|([^\\]\\n]+)\\]")

data class DecodedTz(val instant: Date, val sourceLabel: String)

fun decodeTzTag(text: String): DecodedTz? {
    val m = TZ_TAG_RE.find(text) ?: return null
    val d = parseIsoUtc(m.groupValues[1]) ?: return null
    return DecodedTz(d, m.groupValues[2])
}
