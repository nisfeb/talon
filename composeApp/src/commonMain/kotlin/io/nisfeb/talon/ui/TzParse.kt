package io.nisfeb.talon.ui

import io.nisfeb.talon.util.formatTime12
import io.nisfeb.talon.util.formatWeekdayShort
import io.nisfeb.talon.util.nowMs
import io.nisfeb.talon.util.timeZoneShortLabel
import kotlinx.datetime.DateTimeUnit
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * `/tz <time> [zone]` — parses a time in the sender's (or a
 * user-supplied) timezone and produces an absolute ISO timestamp the
 * other side can re-render in their own zone.
 *
 * Smarter than yap's: the zone arg is optional. When omitted we assume
 * the system default. Times/zones are carried as epoch-millis + an IANA
 * zone id so nothing here reaches for JVM-only java.util.TimeZone.
 */

/** Result of parsing a `/tz` input. */
sealed interface TzParseResult {
    data class Ok(
        val instantMs: Long,
        /** IANA id of the zone the sender typed in (or system default). */
        val sourceZoneId: String,
        /** Short label ("EDT", "PDT", …). */
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
 * to an IANA zone id. Unknown tokens return null so the caller can error.
 */
fun resolveZoneToken(raw: String): String? {
    val t = raw.trim()
    if (t.isEmpty()) return null
    val id = ZONE_ALIASES[t.lowercase()] ?: t
    return runCatching { TimeZone.of(id); id }.getOrNull()
}

/** Short label ("EDT"/"PDT"/…) for a zone id at the given instant. */
fun zoneShortLabel(zoneId: String, atMs: Long = nowMs()): String =
    timeZoneShortLabel(zoneId, atMs)

/**
 * Parse a `/tz` invocation. Accepts "3p", "3pm", "3:30p", "15:00"
 * optionally followed by a zone token. Anchored to "today"; if the
 * resulting instant is already in the past we bump it to tomorrow.
 */
fun parseTzInput(rawArgs: String, nowMs: Long = nowMs()): TzParseResult {
    val args = rawArgs.trim()
    if (args.isEmpty()) {
        return TzParseResult.Err(
            "give a time, e.g. \"/tz 3p\" or \"/tz 3p pacific\""
        )
    }
    val parts = args.split(Regex("\\s+"), limit = 2)
    val timeTok = parts[0]
    val zoneTok = if (parts.size > 1) parts[1] else ""

    val time = parseTimeToken(timeTok)
        ?: return TzParseResult.Err(
            "couldn't parse time \"$timeTok\" — try 3p, 3:30pm, 15:00"
        )

    val zoneId = if (zoneTok.isEmpty()) TimeZone.currentSystemDefault().id
    else resolveZoneToken(zoneTok)
        ?: return TzParseResult.Err(
            "unknown zone \"$zoneTok\" — try pacific / eastern / UTC / or an IANA id"
        )
    val zone = TimeZone.of(zoneId)

    // Anchor to today in the source zone, then bump to tomorrow if the
    // resulting instant is in the past.
    val today = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone).date
    var instantMs = today.atTime(time.start.h, time.start.m)
        .toInstant(zone).toEpochMilliseconds()
    if (instantMs < nowMs) {
        instantMs = today.plus(1, DateTimeUnit.DAY).atTime(time.start.h, time.start.m)
            .toInstant(zone).toEpochMilliseconds()
    }

    return TzParseResult.Ok(instantMs, zoneId, timeZoneShortLabel(zoneId, instantMs))
}

/** ISO-8601 with millisecond precision in UTC. */
fun formatIsoUtc(ms: Long): String {
    val dt = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.UTC)
    fun p(n: Int, w: Int) = n.toString().padStart(w, '0')
    val millis = dt.nanosecond / 1_000_000
    return "${p(dt.year, 4)}-${p(dt.monthNumber, 2)}-${p(dt.dayOfMonth, 2)}T" +
        "${p(dt.hour, 2)}:${p(dt.minute, 2)}:${p(dt.second, 2)}.${p(millis, 3)}Z"
}

/** Parse the ISO string we encoded. Lenient — returns null on bad input. */
fun parseIsoUtc(s: String): Long? =
    runCatching { Instant.parse(s).toEpochMilliseconds() }.getOrNull()

/**
 * Render one viewer-side row: "3:00 PM Eastern (Mon)". Same-day is
 * implicit (no suffix); other days get a short weekday tag.
 */
fun formatInZone(ms: Long, zoneId: String, nowMs: Long = nowMs()): String {
    val zone = TimeZone.of(zoneId)
    val time = formatTime12(ms, zone)
    val target = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone).date
    val today = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone).date
    if (target == today) return time
    return "$time (${formatWeekdayShort(ms, zone)})"
}

/** Machine-readable tag the decoder recognizes. */
fun encodeTzTag(instantIso: String, sourceLabel: String): String =
    "[tz|$instantIso|$sourceLabel]"

val TZ_TAG_RE: Regex = Regex("\\[tz\\|([^|\\]]+)\\|([^\\]\\n]+)\\]")

data class DecodedTz(val instantMs: Long, val sourceLabel: String)

fun decodeTzTag(text: String): DecodedTz? {
    val m = TZ_TAG_RE.find(text) ?: return null
    val ms = parseIsoUtc(m.groupValues[1]) ?: return null
    return DecodedTz(ms, m.groupValues[2])
}
