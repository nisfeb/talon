package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pin the search-query parser. Operators and free text round-trip in
 * any order, malformed operators degrade to keyword text (so the user
 * never sees their input vanish), and the `since:` math is stable
 * against an injected clock.
 */
class SearchFilterTest {

    private val NOW = 1_700_000_000_000L  // arbitrary fixed clock

    private fun parse(q: String, now: Long = NOW) = parseSearchFilter(q, now)

    @Test
    fun `bare keywords pass through as needle, no operators set`() {
        val f = parse("hello world")
        assertEquals("hello world", f.needle)
        assertNull(f.fromShip)
        assertNull(f.inWhom)
        assertNull(f.sinceMs)
        assertEquals(false, f.hasImage)
        assertEquals(false, f.hasLink)
    }

    @Test
    fun `from operator with tilde patp`() {
        val f = parse("from:~darduc-mitfen")
        assertEquals("~darduc-mitfen", f.fromShip)
        assertEquals("", f.needle)
    }

    @Test
    fun `from operator without tilde gets the tilde added`() {
        val f = parse("from:darduc-mitfen")
        assertEquals("~darduc-mitfen", f.fromShip)
    }

    @Test
    fun `from with empty value falls back to keyword`() {
        val f = parse("from: hello")
        // "from:" has empty value → not an operator → keeps in needle.
        // " hello" gets normal split treatment.
        assertEquals("from: hello", f.needle)
        assertNull(f.fromShip)
    }

    @Test
    fun `in operator captures whom verbatim`() {
        val f = parse("in:chat/~ship/channel-name")
        assertEquals("chat/~ship/channel-name", f.inWhom)
    }

    @Test
    fun `since operator with single unit`() {
        val f = parse("since:1w")
        assertEquals(NOW - 7 * 86_400_000L, f.sinceMs)
    }

    @Test
    fun `since operator without numeric prefix defaults to 1`() {
        // "since:d" should be 1 day, not zero or invalid.
        val f = parse("since:d")
        assertEquals(NOW - 86_400_000L, f.sinceMs)
    }

    @Test
    fun `since operator handles mo as month not minute`() {
        val f = parse("since:1mo")
        assertEquals(NOW - 30L * 86_400_000L, f.sinceMs)
    }

    @Test
    fun `since with garbage value falls back to keyword`() {
        val f = parse("since:garbage hello")
        // "since:garbage" doesn't parse → kept as keyword text.
        assertEquals("since:garbage hello", f.needle)
        assertNull(f.sinceMs)
    }

    @Test
    fun `has image and has link`() {
        val f = parse("has:image has:link")
        assertEquals(true, f.hasImage)
        assertEquals(true, f.hasLink)
    }

    @Test
    fun `has accepts plural and shorthand aliases`() {
        assertEquals(true, parse("has:images").hasImage)
        assertEquals(true, parse("has:img").hasImage)
        assertEquals(true, parse("has:links").hasLink)
        assertEquals(true, parse("has:url").hasLink)
    }

    @Test
    fun `unknown has value falls back to keyword`() {
        val f = parse("has:goldfish")
        assertEquals("has:goldfish", f.needle)
        assertEquals(false, f.hasImage)
    }

    @Test
    fun `mixed operators and keywords in any order`() {
        val f = parse("from:~ship hello since:1d in:chat/foo world has:image")
        assertEquals("~ship", f.fromShip)
        assertEquals("chat/foo", f.inWhom)
        assertEquals(NOW - 86_400_000L, f.sinceMs)
        assertEquals(true, f.hasImage)
        assertEquals("hello world", f.needle)
    }

    @Test
    fun `case insensitive operator keys`() {
        val f = parse("FROM:~ship HAS:Image SINCE:1H")
        assertEquals("~ship", f.fromShip)
        assertEquals(true, f.hasImage)
        assertEquals(NOW - 3_600_000L, f.sinceMs)
    }

    @Test
    fun `isTrivial true on empty query`() {
        assertTrue(parse("").isTrivial)
    }

    @Test
    fun `isTrivial true on single character keyword with no operators`() {
        assertTrue(parse("a").isTrivial)
    }

    @Test
    fun `isTrivial false when only operator present`() {
        assertEquals(false, parse("from:~ship").isTrivial)
    }

    @Test
    fun `isTrivial false on two-character keyword`() {
        assertEquals(false, parse("ab").isTrivial)
    }

    @Test
    fun `garbage URL-shaped from is rejected to keyword`() {
        val f = parse("from:http://evil.example/path")
        // Slashes / colons inside don't match patp shape — falls
        // back rather than spoofing the from filter.
        assertEquals("from:http://evil.example/path", f.needle)
        assertNull(f.fromShip)
    }
}
