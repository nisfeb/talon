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
 * Pins `/tz` parsing. Branches that drive user-visible behavior:
 *   - alias map (lowercase short names + IANA ids) resolves
 *   - unknown zone → Err
 *   - empty input → Err
 *   - past time today → bumped to tomorrow
 *   - encode/decode round-trip preserves instant + label
 */
class TzParseTest {

    /** Mon 2024-01-08 10:00 UTC — picked so "3pm UTC" is in the future
     *  and "3am UTC" is in the past on the same day. */
    private val now: Date = isoUtc("2024-01-08T10:00:00.000Z")

    @Test
    fun `resolveZoneToken accepts lowercase short alias`() {
        assertEquals(
            TimeZone.getTimeZone("America/New_York").id,
            resolveZoneToken("eastern")?.id,
        )
    }

    @Test
    fun `resolveZoneToken accepts uppercase short alias`() {
        // Aliases are stored lowercase; resolveZoneToken lowercases first.
        assertEquals(
            TimeZone.getTimeZone("America/Los_Angeles").id,
            resolveZoneToken("PDT")?.id,
        )
    }

    @Test
    fun `resolveZoneToken accepts IANA id directly`() {
        assertEquals(
            "America/New_York",
            resolveZoneToken("America/New_York")?.id,
        )
    }

    @Test
    fun `resolveZoneToken returns null for unknown token`() {
        // Critical: TimeZone.getTimeZone returns GMT for unknown ids
        // (silent fallback). resolveZoneToken must guard against that
        // so unknown zones surface a user-facing error instead of
        // silently picking GMT.
        assertNull(resolveZoneToken("notazone"))
    }

    @Test
    fun `resolveZoneToken returns null for empty token`() {
        assertNull(resolveZoneToken("   "))
    }

    @Test
    fun `parseTzInput empty args is Err`() {
        val r = parseTzInput("", now = now)
        assertTrue(r is TzParseResult.Err)
    }

    @Test
    fun `parseTzInput unparseable time is Err`() {
        val r = parseTzInput("notatime utc", now = now)
        assertTrue(r is TzParseResult.Err)
        assertTrue((r as TzParseResult.Err).error.contains("notatime"))
    }

    @Test
    fun `parseTzInput unknown zone is Err with the bad token quoted`() {
        val r = parseTzInput("3p notazone", now = now)
        assertTrue(r is TzParseResult.Err)
        assertTrue((r as TzParseResult.Err).error.contains("notazone"))
    }

    @Test
    fun `parseTzInput future time today resolves to today`() {
        // 3pm UTC on a 10:00 UTC anchor → today 15:00 UTC.
        val r = parseTzInput("3p utc", now = now)
        assertTrue(r is TzParseResult.Ok)
        r as TzParseResult.Ok
        val expected = isoUtc("2024-01-08T15:00:00.000Z")
        assertEquals(expected, r.instant)
        assertEquals("UTC", r.sourceZone.id)
    }

    @Test
    fun `parseTzInput past time today is bumped to tomorrow`() {
        // 3am UTC on a 10:00 UTC anchor → tomorrow 03:00 UTC.
        val r = parseTzInput("3:00 utc", now = now)
        assertTrue(r is TzParseResult.Ok)
        r as TzParseResult.Ok
        val expected = isoUtc("2024-01-09T03:00:00.000Z")
        assertEquals(expected, r.instant)
    }

    @Test
    fun `parseTzInput omitted zone uses system default`() {
        val r = parseTzInput("3p", now = now)
        assertTrue(r is TzParseResult.Ok)
        assertEquals(TimeZone.getDefault().id, (r as TzParseResult.Ok).sourceZone.id)
    }

    @Test
    fun `encodeTzTag and decodeTzTag round-trip preserves instant and label`() {
        val instantIso = "2024-01-08T15:00:00.000Z"
        val tag = encodeTzTag(instantIso, "EDT")
        val decoded = decodeTzTag("Some prefix\n$tag\nsuffix")
        assertNotNull(decoded)
        assertEquals(isoUtc(instantIso), decoded!!.instant)
        assertEquals("EDT", decoded.sourceLabel)
    }

    @Test
    fun `decodeTzTag returns null for malformed instant`() {
        val tag = "[tz|notatime|EDT]"
        assertNull(decodeTzTag(tag))
    }

    @Test
    fun `decodeTzTag returns null when tag is absent from text`() {
        assertNull(decodeTzTag("nothing here"))
    }

    @Test
    fun `parseTzInput accepts multi-word zone like america new_york`() {
        // The split is `limit = 2` so anything after the first whitespace
        // is treated as the zone token; alias map handles "ny" / IANA
        // accepts "America/New_York" directly.
        val r = parseTzInput("3p America/New_York", now = now)
        assertTrue(r is TzParseResult.Ok)
        assertEquals("America/New_York", (r as TzParseResult.Ok).sourceZone.id)
    }

    // ── helpers ─────────────────────────────────────────────────────

    private fun isoUtc(s: String): Date = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US,
    ).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s)!!
}
