package io.nisfeb.talon.ui

import kotlinx.datetime.TimeZone
import org.junit.Test
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

    private fun isoUtc(s: String): Long = parseIsoUtc(s)!!

    /** Mon 2024-01-08 10:00 UTC — picked so "3pm UTC" is in the future
     *  and "3am UTC" is in the past on the same day. */
    private val now: Long = isoUtc("2024-01-08T10:00:00.000Z")

    @Test
    fun `resolveZoneToken accepts lowercase short alias`() {
        assertEquals("America/New_York", resolveZoneToken("eastern"))
    }

    @Test
    fun `resolveZoneToken accepts uppercase short alias`() {
        // Aliases are stored lowercase; resolveZoneToken lowercases first.
        assertEquals("America/Los_Angeles", resolveZoneToken("PDT"))
    }

    @Test
    fun `resolveZoneToken accepts IANA id directly`() {
        assertEquals("America/New_York", resolveZoneToken("America/New_York"))
    }

    @Test
    fun `resolveZoneToken returns null for unknown token`() {
        // Unknown ids must surface as null (→ user-facing error), not a
        // silent fallback zone.
        assertNull(resolveZoneToken("notazone"))
    }

    @Test
    fun `resolveZoneToken returns null for empty token`() {
        assertNull(resolveZoneToken("   "))
    }

    @Test
    fun `parseTzInput empty args is Err`() {
        val r = parseTzInput("", nowMs = now)
        assertTrue(r is TzParseResult.Err)
    }

    @Test
    fun `parseTzInput unparseable time is Err`() {
        val r = parseTzInput("notatime utc", nowMs = now)
        assertTrue(r is TzParseResult.Err)
        assertTrue((r as TzParseResult.Err).error.contains("notatime"))
    }

    @Test
    fun `parseTzInput unknown zone is Err with the bad token quoted`() {
        val r = parseTzInput("3p notazone", nowMs = now)
        assertTrue(r is TzParseResult.Err)
        assertTrue((r as TzParseResult.Err).error.contains("notazone"))
    }

    @Test
    fun `parseTzInput future time today resolves to today`() {
        // 3pm UTC on a 10:00 UTC anchor → today 15:00 UTC.
        val r = parseTzInput("3p utc", nowMs = now)
        assertTrue(r is TzParseResult.Ok)
        r as TzParseResult.Ok
        assertEquals(isoUtc("2024-01-08T15:00:00.000Z"), r.instantMs)
        assertEquals("UTC", r.sourceZoneId)
    }

    @Test
    fun `parseTzInput past time today is bumped to tomorrow`() {
        // 3am UTC on a 10:00 UTC anchor → tomorrow 03:00 UTC.
        val r = parseTzInput("3:00 utc", nowMs = now)
        assertTrue(r is TzParseResult.Ok)
        r as TzParseResult.Ok
        assertEquals(isoUtc("2024-01-09T03:00:00.000Z"), r.instantMs)
    }

    @Test
    fun `parseTzInput omitted zone uses system default`() {
        val r = parseTzInput("3p", nowMs = now)
        assertTrue(r is TzParseResult.Ok)
        assertEquals(TimeZone.currentSystemDefault().id, (r as TzParseResult.Ok).sourceZoneId)
    }

    @Test
    fun `encodeTzTag and decodeTzTag round-trip preserves instant and label`() {
        val instantIso = "2024-01-08T15:00:00.000Z"
        val tag = encodeTzTag(instantIso, "EDT")
        val decoded = decodeTzTag("Some prefix\n$tag\nsuffix")
        assertNotNull(decoded)
        assertEquals(isoUtc(instantIso), decoded!!.instantMs)
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
        val r = parseTzInput("3p America/New_York", nowMs = now)
        assertTrue(r is TzParseResult.Ok)
        assertEquals("America/New_York", (r as TzParseResult.Ok).sourceZoneId)
    }
}
