package io.nisfeb.talon.ui

import java.util.Date
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip and decode tests for the four widget tag formats we send
 * as slash commands: `[tz|…]`, `[cal|…]`, `[poll|…]`, `[loc|…]`.
 * These tags travel through Story content as plain inline text, so
 * the regex alone is our wire contract — if it changes, every
 * already-sent widget in Talon's history goes dark.
 */
class WidgetDecodersTest {

    // ─── TzParse ───────────────────────────────────────────────

    @Test
    fun `encode then decode tz tag round-trips instant + label`() {
        val iso = "2026-04-24T15:00:00.000Z"
        val tag = encodeTzTag(iso, "sampel HQ")
        val decoded = decodeTzTag(tag)!!
        assertNotNull(decoded)
        assertEquals(parseIsoUtc(iso)!!.time, decoded.instant.time)
        assertEquals("sampel HQ", decoded.sourceLabel)
    }

    @Test
    fun `tz tag regex matches the exact shape`() {
        val matches = TZ_TAG_RE.findAll(
            "hi [tz|2026-04-24T00:00:00.000Z|UTC] there",
        ).toList()
        assertEquals(1, matches.size)
    }

    @Test
    fun `parseIsoUtc round-trips formatIsoUtc`() {
        val now = Date()
        val iso = formatIsoUtc(now)
        val back = parseIsoUtc(iso)!!
        // Format drops sub-second precision, so compare at second
        // resolution.
        assertEquals(now.time / 1000, back.time / 1000)
    }

    @Test
    fun `parseIsoUtc returns null on garbage`() {
        assertNull(parseIsoUtc("not-a-date"))
    }

    // ─── CalParse ──────────────────────────────────────────────

    @Test
    fun `cal tag round-trips start, end, title`() {
        val start = parseIsoUtc("2026-04-24T15:00:00.000Z")!!
        val end = parseIsoUtc("2026-04-24T16:00:00.000Z")!!
        val tag = encodeCalTag(start, end, "Standup")
        val decoded = decodeCalTag(tag)!!
        // Seconds precision — same as format.
        assertEquals(start.time / 1000, decoded.start.time / 1000)
        assertEquals(end.time / 1000, decoded.end.time / 1000)
        assertEquals("Standup", decoded.title)
    }

    @Test
    fun `empty title decodes to the Event fallback label`() {
        val start = parseIsoUtc("2026-04-24T15:00:00.000Z")!!
        val end = parseIsoUtc("2026-04-24T16:00:00.000Z")!!
        val tag = encodeCalTag(start, end, "")
        val decoded = decodeCalTag(tag)!!
        // Empty title survives the encoder, but decodeCalTag replaces
        // blanks with the "Event" fallback so rendering has something
        // to show.
        assertEquals("Event", decoded.title)
    }

    @Test
    fun `url-encoded title with spaces and punctuation round-trips`() {
        val start = parseIsoUtc("2026-04-24T15:00:00.000Z")!!
        val end = parseIsoUtc("2026-04-24T16:00:00.000Z")!!
        val tag = encodeCalTag(start, end, "Design review: Q2 plans")
        val decoded = decodeCalTag(tag)!!
        assertEquals("Design review: Q2 plans", decoded.title)
    }

    @Test
    fun `cal tag decoder returns null on malformed tag`() {
        assertNull(decodeCalTag("[cal|not|iso|x]"))
        assertNull(decodeCalTag("no tag here"))
    }

    // ─── PollFormat ────────────────────────────────────────────

    @Test
    fun `poll round-trips question and up to 8 options`() {
        val poll = Poll(
            question = "Lunch?",
            options = listOf("Tacos", "Pizza", "Salad"),
        )
        val tag = encodePollTag(poll)
        val back = decodePollTag(tag)!!
        assertEquals("Lunch?", back.question)
        assertEquals(listOf("Tacos", "Pizza", "Salad"), back.options)
    }

    @Test
    fun `poll regex is strict about closing bracket`() {
        val tag = encodePollTag(Poll("Q", listOf("A")))
        // Tag without closing ] doesn't parse.
        val broken = tag.removeSuffix("]")
        assertNull(decodePollTag(broken))
    }

    // ─── LocationShare ────────────────────────────────────────

    @Test
    fun `loc tag round-trips lat and lng with fractional values`() {
        val tag = encodeLocTag(lat = 37.7749, lng = -122.4194)
        val d = decodeLocTag(tag)!!
        assertEquals(37.7749, d.lat, 0.00001)
        assertEquals(-122.4194, d.lng, 0.00001)
    }

    @Test
    fun `loc tag handles integer coords`() {
        val tag = encodeLocTag(0.0, 0.0)
        val d = decodeLocTag(tag)!!
        assertEquals(0.0, d.lat, 0.0)
        assertEquals(0.0, d.lng, 0.0)
    }

    @Test
    fun `loc tag regex accepts negative coords`() {
        val d = decodeLocTag("[loc|-37.8|-122.4]")!!
        assertTrue(d.lat < 0)
        assertTrue(d.lng < 0)
    }

    @Test
    fun `loc tag regex rejects bogus formats`() {
        assertNull(decodeLocTag("[loc|abc|def]"))
        assertNull(decodeLocTag("[loc|37.7749]"))  // missing lng
    }

    @Test
    fun `osmViewerUrl embeds coords and zoom`() {
        val url = osmViewerUrl(37.7749, -122.4194, zoom = 14)
        assertTrue(url.contains("37.77490"))
        assertTrue(url.contains("-122.41940"))
        // OSM fragment format: `#map=<zoom>/<lat>/<lng>`.
        assertTrue(url.contains("map=14/"))
    }
}
