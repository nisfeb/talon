package io.nisfeb.talon.ui

import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the natural-language `/cal` parser. Branches that drive
 * user-visible behavior:
 *   - dateless input falls back to "today" (defaultDate=true)
 *   - timeless input picks the next hour, 60-min default duration
 *   - weekday tokens roll forward to the next future occurrence
 *   - the "h in 1..7 → bump to PM" rule when no minutes / no AM/PM
 *   - encode/decode round-trip preserves title (URL-encoded)
 */
class CalParseTest {

    /** Anchor for time-relative cases — Sunday 2024-01-07T10:00:00 local. */
    private val now: Date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        .apply { timeZone = TimeZone.getDefault() }
        .parse("2024-01-07T10:00:00")!!

    @Test
    fun `empty input is an error`() {
        val r = parseCalText("   ", now = now)
        assertTrue(r is CalParseResult.Err)
    }

    @Test
    fun `weekday plus range with title parses end and title`() {
        // "thurs" → next Thursday from Sun Jan 7; "2-3p" → 14:00..15:00; rest is title.
        val r = parseCalText("thurs 2-3p Meet John", now = now)
        assertTrue(r is CalParseResult.Ok)
        r as CalParseResult.Ok
        assertEquals("Meet John", r.title)
        assertCalField(r.start, Calendar.HOUR_OF_DAY, 14)
        assertCalField(r.start, Calendar.MINUTE, 0)
        assertCalField(r.end, Calendar.HOUR_OF_DAY, 15)
        assertCalField(r.end, Calendar.MINUTE, 0)
        // Sunday Jan 7 + 4 days = Thursday Jan 11.
        assertCalField(r.start, Calendar.DAY_OF_MONTH, 11)
        assertEquals(false, r.defaultDate)
        assertEquals(false, r.defaultDuration)
    }

    @Test
    fun `iso date plus 24h time parses without ampm bump`() {
        val r = parseCalText("2024-03-15 15:30 Standup", now = now)
        assertTrue(r is CalParseResult.Ok)
        r as CalParseResult.Ok
        assertCalField(r.start, Calendar.YEAR, 2024)
        assertCalField(r.start, Calendar.MONTH, Calendar.MARCH)
        assertCalField(r.start, Calendar.DAY_OF_MONTH, 15)
        assertCalField(r.start, Calendar.HOUR_OF_DAY, 15)
        assertCalField(r.start, Calendar.MINUTE, 30)
        // No end given → 60-min default.
        assertEquals(60L * 60_000L, r.end.time - r.start.time)
        assertEquals(true, r.defaultDuration)
        assertEquals("Standup", r.title)
    }

    @Test
    fun `bare hour 1 to 7 with no ampm is bumped to PM`() {
        // "3" with no minutes and no AM/PM → 15:00. The rule exists because
        // typing "/cal 3 Meet" almost always means 3 PM, not 3 AM.
        val tr = parseTimeToken("3")
        assertNotNull(tr)
        assertEquals(15, tr!!.start.h)
        assertEquals(0, tr.start.m)
        assertNull(tr.end)
    }

    @Test
    fun `bare hour 8 with no ampm is left at 8 AM`() {
        // The bump only applies for h in 1..7 — 8 stays 8 AM.
        val tr = parseTimeToken("8")
        assertNotNull(tr)
        assertEquals(8, tr!!.start.h)
    }

    @Test
    fun `bare hour with minutes is left literal`() {
        // "3:00" has minutes → not bumped (rule requires hasMinutes==false).
        val tr = parseTimeToken("3:00")
        assertNotNull(tr)
        assertEquals(3, tr!!.start.h)
    }

    @Test
    fun `time range with bare digits and no ampm assumes pm crossover`() {
        // "9-5" with no AM/PM: end (5) < start (9) → bump end by 12 → 17:00.
        val tr = parseTimeToken("9-5")
        assertNotNull(tr)
        assertEquals(9, tr!!.start.h)
        assertEquals(17, tr.end!!.h)
    }

    @Test
    fun `range pm marker on one side propagates to the other`() {
        // "2-3p" → both sides PM (14:00..15:00).
        val tr = parseTimeToken("2-3p")
        assertNotNull(tr)
        assertEquals(14, tr!!.start.h)
        assertEquals(15, tr.end!!.h)
    }

    @Test
    fun `default title is Event when only date and time given`() {
        val r = parseCalText("tomorrow 2pm", now = now)
        assertTrue(r is CalParseResult.Ok)
        assertEquals("Event", (r as CalParseResult.Ok).title)
    }

    @Test
    fun `tomorrow rolls forward one day`() {
        val r = parseCalText("tomorrow 2pm Plan", now = now)
        assertTrue(r is CalParseResult.Ok)
        // Sun Jan 7 → Mon Jan 8.
        assertCalField((r as CalParseResult.Ok).start, Calendar.DAY_OF_MONTH, 8)
    }

    @Test
    fun `noise tokens are stripped from the title`() {
        // "at" / "on" / "from" are noise; they don't end up in the title.
        val r = parseCalText("on tomorrow at 2pm Plan", now = now)
        assertTrue(r is CalParseResult.Ok)
        assertEquals("Plan", (r as CalParseResult.Ok).title)
    }

    @Test
    fun `encodeCalTag and decodeCalTag round-trip preserves title`() {
        // Ensures the URL-encoder + decoder match. Title with spaces +
        // a `&` would break a naive encoder; title with `+` would break
        // a decoder that maps `+` back to space.
        val start = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse("2024-03-15T19:30:00.000Z")!!
        val end = Date(start.time + 60L * 60_000L)
        val title = "Lunch & demo: q1 review"
        val tag = encodeCalTag(start, end, title)

        val decoded = decodeCalTag("Some prefix\n$tag\nsuffix")
        assertNotNull(decoded)
        assertEquals(title, decoded!!.title)
        assertEquals(start, decoded.start)
        assertEquals(end, decoded.end)
    }

    @Test
    fun `decodeCalTag with empty title falls back to Event`() {
        // The decoder falls back to "Event" when the URL-decoded title
        // is blank — mirrors the parser's default-when-no-title rule.
        val start = "2024-03-15T19:30:00.000Z"
        val end = "2024-03-15T20:30:00.000Z"
        val tag = "[cal|$start|$end|]"
        val decoded = decodeCalTag(tag)
        assertNotNull(decoded)
        assertEquals("Event", decoded!!.title)
    }

    @Test
    fun `decodeCalTag returns null for malformed timestamps`() {
        val tag = "[cal|notatime|2024-03-15T20:30:00.000Z|x]"
        assertNull(decodeCalTag(tag))
    }

    // ── helpers ─────────────────────────────────────────────────────

    private fun assertCalField(d: Date, field: Int, expected: Int) {
        val c = Calendar.getInstance().apply { time = d }
        assertEquals(expected, c.get(field), "Calendar field=$field on $d")
    }
}
