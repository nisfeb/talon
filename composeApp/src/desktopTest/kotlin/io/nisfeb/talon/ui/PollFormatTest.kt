package io.nisfeb.talon.ui

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins `/poll` parsing + the encode/decode tag pair. The escape rules
 * for `|` and `]` are critical — without them an option containing
 * either char would silently corrupt downstream votes.
 */
class PollFormatTest {

    @Test
    fun `parsePollInput rejects fewer than 2 options`() {
        // "q | a" has one option → Err.
        val r = parsePollInput("q | a")
        assertTrue(r is PollParseResult.Err)
    }

    @Test
    fun `parsePollInput rejects only a question`() {
        val r = parsePollInput("just a question?")
        assertTrue(r is PollParseResult.Err)
    }

    @Test
    fun `parsePollInput accepts the minimum of 2 options`() {
        val r = parsePollInput("q? | a | b")
        assertTrue(r is PollParseResult.Ok)
        r as PollParseResult.Ok
        assertEquals("q?", r.poll.question)
        assertEquals(listOf("a", "b"), r.poll.options)
    }

    @Test
    fun `parsePollInput rejects more than 10 options`() {
        // Vote emojis only go up to 10. Eleven options → Err.
        val opts = (1..11).joinToString(" | ") { "opt$it" }
        val r = parsePollInput("q? | $opts")
        assertTrue(r is PollParseResult.Err)
        assertTrue((r as PollParseResult.Err).error.contains("$MAX_POLL_OPTIONS"))
    }

    @Test
    fun `parsePollInput accepts exactly 10 options`() {
        val opts = (1..10).joinToString(" | ") { "opt$it" }
        val r = parsePollInput("q? | $opts")
        assertTrue(r is PollParseResult.Ok)
        assertEquals(10, (r as PollParseResult.Ok).poll.options.size)
    }

    @Test
    fun `parsePollInput rejects an option longer than 120 chars`() {
        val long = "x".repeat(121)
        val r = parsePollInput("q? | a | $long")
        assertTrue(r is PollParseResult.Err)
    }

    @Test
    fun `parsePollInput rejects a question longer than 240 chars`() {
        val longQ = "?".repeat(241)
        val r = parsePollInput("$longQ | a | b")
        assertTrue(r is PollParseResult.Err)
    }

    @Test
    fun `parsePollInput trims whitespace around segments`() {
        val r = parsePollInput("   q?   |   a   |   b   ")
        assertTrue(r is PollParseResult.Ok)
        r as PollParseResult.Ok
        assertEquals("q?", r.poll.question)
        assertEquals(listOf("a", "b"), r.poll.options)
    }

    @Test
    fun `encode and decode round-trip on plain options`() {
        val poll = Poll("Best lunch?", listOf("Tacos", "Pizza", "Salad"))
        val tag = encodePollTag(poll)
        val decoded = decodePollTag("Body prefix\n$tag\nbody suffix")
        assertNotNull(decoded)
        assertEquals(poll.question, decoded!!.question)
        assertEquals(poll.options, decoded.options)
    }

    @Test
    fun `encode escapes pipe and bracket inside options`() {
        // Both `|` (option separator) and `]` (tag terminator) must be
        // escaped — without it, "a|b" splits into 2 options and "a]b"
        // truncates the tag mid-stream.
        val poll = Poll("q?", listOf("a|b", "c]d"))
        val tag = encodePollTag(poll)
        // The raw tag should not contain unescaped pipes inside options.
        // Position of the question's `?` is followed only by escape codes
        // and the literal `|` separators between segments.
        assertTrue(tag.contains("a%7Cb"), "unescaped pipe in tag: $tag")
        assertTrue(tag.contains("c%5Dd"), "unescaped bracket in tag: $tag")
    }

    @Test
    fun `decode reverses the pipe and bracket escapes`() {
        val poll = Poll("q with | pipe", listOf("a|b", "c]d", "ok"))
        val decoded = decodePollTag(encodePollTag(poll))
        assertNotNull(decoded)
        assertEquals(poll.question, decoded!!.question)
        assertEquals(poll.options, decoded.options)
    }

    @Test
    fun `decode returns null when tag is absent`() {
        assertNull(decodePollTag("no poll here"))
    }

    @Test
    fun `decode returns null when tag has fewer than 3 segments`() {
        // [poll|q|a] is question + one option → 2 segments → invalid.
        assertNull(decodePollTag("[poll|q|a]"))
    }

    @Test
    fun `vote emojis match max poll options length`() {
        // The emoji list and the option cap must move together. A
        // mismatch here means runPoll's mapIndexed would index out
        // of bounds for an N-option poll where N == VOTE_EMOJIS.size + 1.
        assertEquals(MAX_POLL_OPTIONS, VOTE_EMOJIS.size)
    }
}
