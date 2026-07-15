package io.nisfeb.talon.ui

import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.junit.Test
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

    private val zone = TimeZone.currentSystemDefault()

    /** Anchor for time-relative cases — Sunday 2024-01-07T10:00:00 local. */
    private val now: Long =
        LocalDateTime(2024, 1, 7, 10, 0, 0).toInstant(zone).toEpochMilliseconds()

    private fun ldt(ms: Long): LocalDateTime =
        Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone)

    @Test
    fun `empty input is an error`() {
        val r = parseCalText("   ", nowMs = now)
        assertTrue(r is CalParseResult.Err)
    }

    @Test
    fun `weekday plus range with title parses end and title`() {
        val r = parseCalText("thurs 2-3p Meet John", nowMs = now)
        assertTrue(r is CalParseResult.Ok)
        r as CalParseResult.Ok
        assertEquals("Meet John", r.title)
        assertEquals(14, ldt(r.startMs).hour)
        assertEquals(0, ldt(r.startMs).minute)
        assertEquals(15, ldt(r.endMs).hour)
        assertEquals(0, ldt(r.endMs).minute)
        // Sunday Jan 7 + 4 days = Thursday Jan 11.
        assertEquals(11, ldt(r.startMs).dayOfMonth)
        assertEquals(false, r.defaultDate)
        assertEquals(false, r.defaultDuration)
    }

    @Test
    fun `iso date plus 24h time parses without ampm bump`() {
        val r = parseCalText("2024-03-15 15:30 Standup", nowMs = now)
        assertTrue(r is CalParseResult.Ok)
        r as CalParseResult.Ok
        assertEquals(2024, ldt(r.startMs).year)
        assertEquals(3, ldt(r.startMs).monthNumber)
        assertEquals(15, ldt(r.startMs).dayOfMonth)
        assertEquals(15, ldt(r.startMs).hour)
        assertEquals(30, ldt(r.startMs).minute)
        // No end given → 60-min default.
        assertEquals(60L * 60_000L, r.endMs - r.startMs)
        assertEquals(true, r.defaultDuration)
        assertEquals("Standup", r.title)
    }

    @Test
    fun `bare hour 1 to 7 with no ampm is bumped to PM`() {
        val tr = parseTimeToken("3")
        assertNotNull(tr)
        assertEquals(15, tr!!.start.h)
        assertEquals(0, tr.start.m)
        assertNull(tr.end)
    }

    @Test
    fun `bare hour 8 with no ampm is left at 8 AM`() {
        val tr = parseTimeToken("8")
        assertNotNull(tr)
        assertEquals(8, tr!!.start.h)
    }

    @Test
    fun `bare hour with minutes is left literal`() {
        val tr = parseTimeToken("3:00")
        assertNotNull(tr)
        assertEquals(3, tr!!.start.h)
    }

    @Test
    fun `time range with bare digits and no ampm assumes pm crossover`() {
        val tr = parseTimeToken("9-5")
        assertNotNull(tr)
        assertEquals(9, tr!!.start.h)
        assertEquals(17, tr.end!!.h)
    }

    @Test
    fun `range pm marker on one side propagates to the other`() {
        val tr = parseTimeToken("2-3p")
        assertNotNull(tr)
        assertEquals(14, tr!!.start.h)
        assertEquals(15, tr.end!!.h)
    }

    @Test
    fun `range with both sides marked am and pm preserves each independently`() {
        val tr = parseTimeToken("2am-3pm")
        assertNotNull(tr)
        assertEquals(2, tr!!.start.h)
        assertEquals(15, tr.end!!.h)
    }

    @Test
    fun `12 am parses to hour 0 not hour 12`() {
        val tr = parseTimeToken("12am")
        assertNotNull(tr)
        assertEquals(0, tr!!.start.h)
    }

    @Test
    fun `12 pm parses to hour 12 not hour 0`() {
        val tr = parseTimeToken("12pm")
        assertNotNull(tr)
        assertEquals(12, tr!!.start.h)
    }

    @Test
    fun `range 2pm-3 propagates pm marker forward to second side`() {
        val tr = parseTimeToken("2pm-3")
        assertNotNull(tr)
        assertEquals(14, tr!!.start.h)
        assertEquals(15, tr.end!!.h)
    }

    @Test
    fun `parseDateToken rejects out-of-range month or day`() {
        val r = parseCalText("13/15/2024 2pm Plan", nowMs = now)
        assertTrue(r is CalParseResult.Ok)
        r as CalParseResult.Ok
        assertEquals(true, r.defaultDate)
        assertTrue(
            r.title.contains("13/15/2024"),
            "title should preserve the bad date token: ${r.title}",
        )
    }

    @Test
    fun `parseCalText with no time defaults to next hour not current hour`() {
        // now is 2024-01-07T10:00:00 (Sunday) — see field declaration.
        val r = parseCalText("Plan", nowMs = now)
        assertTrue(r is CalParseResult.Ok)
        assertEquals(11, ldt((r as CalParseResult.Ok).startMs).hour)
    }

    @Test
    fun `parseDateToken US format preserves user-specified year literally`() {
        val r = parseCalText("3/15/2025 2pm Plan", nowMs = now)
        assertTrue(r is CalParseResult.Ok)
        r as CalParseResult.Ok
        assertEquals(2025, ldt(r.startMs).year)
        assertEquals(3, ldt(r.startMs).monthNumber)
        assertEquals(15, ldt(r.startMs).dayOfMonth)
    }

    @Test
    fun `parseCalText with end before start in same day rolls end to next day`() {
        val r = parseCalText("tomorrow 2-3p Meet", nowMs = now)
        assertTrue(r is CalParseResult.Ok)
        r as CalParseResult.Ok
        // tomorrow is Jan 8 from the Sun Jan 7 anchor. Both start AND end on Jan 8.
        assertEquals(8, ldt(r.startMs).dayOfMonth)
        assertEquals(8, ldt(r.endMs).dayOfMonth)
    }

    @Test
    fun `default title is Event when only date and time given`() {
        val r = parseCalText("tomorrow 2pm", nowMs = now)
        assertTrue(r is CalParseResult.Ok)
        assertEquals("Event", (r as CalParseResult.Ok).title)
    }

    @Test
    fun `tomorrow rolls forward one day`() {
        val r = parseCalText("tomorrow 2pm Plan", nowMs = now)
        assertTrue(r is CalParseResult.Ok)
        assertEquals(8, ldt((r as CalParseResult.Ok).startMs).dayOfMonth)
    }

    @Test
    fun `noise tokens are stripped from the title`() {
        val r = parseCalText("on tomorrow at 2pm Plan", nowMs = now)
        assertTrue(r is CalParseResult.Ok)
        assertEquals("Plan", (r as CalParseResult.Ok).title)
    }

    @Test
    fun `encodeCalTag and decodeCalTag round-trip preserves title`() {
        val startMs = parseIsoUtc("2024-03-15T19:30:00.000Z")!!
        val endMs = startMs + 60L * 60_000L
        val title = "Lunch & demo: q1 review"
        val tag = encodeCalTag(startMs, endMs, title)

        val decoded = decodeCalTag("Some prefix\n$tag\nsuffix")
        assertNotNull(decoded)
        assertEquals(title, decoded!!.title)
        assertEquals(startMs, decoded.startMs)
        assertEquals(endMs, decoded.endMs)
    }

    @Test
    fun `decodeCalTag with empty title falls back to Event`() {
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
}
