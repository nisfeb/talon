package io.nisfeb.talon.urbit

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Round-trip the standard inline + block shapes produced by
 * [Markdown.parseInlines] and [MarkdownBlocks.toStory]. These pin the
 * "Copy as Markdown" output against the wire format the ship sends.
 *
 * Where a precise round-trip isn't reasonable (e.g. trailing whitespace
 * collapse), the assertions match the markdown a user would type to
 * produce the same Story — that's the point of the feature.
 */
class RawMarkdownTest {

    private fun render(md: String): String {
        // Build the wire JSON the way the chat path does: one inline
        // verse per non-blank paragraph.
        val story = MarkdownBlocks.toStory(md)
        return RawMarkdown.fromStory(story)
    }

    @Test
    fun `plain text round-trips`() {
        assertEquals("hello world", render("hello world"))
    }

    @Test
    fun `bold round-trips`() {
        assertEquals("**hi**", render("**hi**"))
    }

    @Test
    fun `italic round-trips with asterisk`() {
        // Tokenizer normalizes `_x_` → italic span; renderer emits `*x*`.
        assertEquals("*emphasis*", render("*emphasis*"))
        assertEquals("*emphasis*", render("_emphasis_"))
    }

    @Test
    fun `strike round-trips`() {
        assertEquals("~~gone~~", render("~~gone~~"))
    }

    @Test
    fun `inline code round-trips`() {
        assertEquals("`code()`", render("`code()`"))
    }

    @Test
    fun `link round-trips with both label and href`() {
        assertEquals("[click](https://example.com)", render("[click](https://example.com)"))
    }

    @Test
    fun `bare ship mention round-trips`() {
        assertEquals("~mister-botter", render("~mister-botter"))
    }

    @Test
    fun `mixed inline styles round-trip`() {
        assertEquals("**bold** and *italic*", render("**bold** and *italic*"))
    }

    @Test
    fun `heading h2 round-trips`() {
        assertEquals("## Section", render("## Section"))
    }

    @Test
    fun `fenced code block round-trips with language`() {
        val src = "```kotlin\nfun x() = 1\n```"
        assertEquals(src, render(src))
    }

    @Test
    fun `blockquote round-trips line-prefixed`() {
        assertEquals("> a quote", render("> a quote"))
    }

    @Test
    fun `horizontal rule round-trips`() {
        assertEquals("---", render("---"))
    }

    @Test
    fun `multiple paragraphs separated by blank line`() {
        assertEquals("first\n\nsecond", render("first\n\nsecond"))
    }

    @Test
    fun `unrecognized json returns empty string instead of throwing`() {
        assertEquals("", RawMarkdown.fromStoryJson("{not valid json"))
    }

    @Test
    fun `null story returns empty`() {
        assertEquals("", RawMarkdown.fromStory(null))
    }
}
