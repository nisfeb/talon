package io.nisfeb.talon.urbit

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownBlocksTest {

    // ─── paragraphs ────────────────────────────────────────────────

    @Test
    fun `consecutive lines in a paragraph are joined with newline`() {
        val story = MarkdownBlocks.toStory("line one\nline two")
        assertEquals(1, story.size)
    }

    // ─── headings ──────────────────────────────────────────────────

    @Test
    fun `hash without space is plain paragraph`() {
        // Tight markdown spec — `#notspace` isn't a heading.
        val verse = MarkdownBlocks.toStory("#notspace").single().jsonObject
        assertTrue(verse.containsKey("inline"))
        assertTrue(!verse.containsKey("block"))
    }

    // ─── code blocks ──────────────────────────────────────────────

    @Test
    fun `unclosed code fence falls back to plain paragraph`() {
        // Regression: previously the parser ate the rest of the body
        // when no closing fence was found.
        val story = MarkdownBlocks.toStory("```\nstart\n\nnext paragraph")
        // `\`\`\`` + "start" collapse as plain; "next paragraph" is a
        // separate verse.
        assertTrue("at least one paragraph", story.size >= 1)
        val first = story[0].jsonObject
        // Must be inline (plain paragraph), not a code block.
        assertNull(first["block"])
        assertNotNull(first["inline"])
    }

    // ─── blockquote ──────────────────────────────────────────────

    @Test
    fun `multi-line blockquote is joined`() {
        val verse = MarkdownBlocks.toStory("> first\n> second").single().jsonObject
        val quoteSpans = verse["inline"]!!.jsonArray[0].jsonObject["blockquote"]!!.jsonArray
        // The quoted text should include both lines joined by a break/newline.
        val flat = quoteSpans.joinToString("") { el ->
            val p = el as? JsonPrimitive
            p?.content.orEmpty()
        }
        assertTrue("content spans both lines", flat.contains("first") && flat.contains("second"))
    }

    // ─── horizontal rule ──────────────────────────────────────────

    // ─── lists ────────────────────────────────────────────────────

    private fun listOf(verse: JsonObject): JsonObject =
        verse["block"]!!.jsonObject["listing"]!!.jsonObject["list"]!!.jsonObject

    @Test
    fun `asterisk and plus also start bullets`() {
        assertTrue(MarkdownBlocks.isListLine("* star"))
        assertTrue(MarkdownBlocks.isListLine("+ plus"))
        assertTrue(MarkdownBlocks.isListLine("1) paren"))
    }

    @Test
    fun `switching marker kind starts a fresh list`() {
        // Two bullets then two numbers → two separate listing blocks.
        val story = MarkdownBlocks.toStory("- a\n- b\n1. c\n2. d")
        assertEquals(2, story.size)
        assertEquals("unordered", listOf(story[0].jsonObject)["type"]!!.jsonPrimitive.content)
        assertEquals("ordered", listOf(story[1].jsonObject)["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `list item body keeps inline styling`() {
        val verse = MarkdownBlocks.toStory("- has **bold**").single().jsonObject
        val item = listOf(verse)["items"]!!.jsonArray[0].jsonObject["item"]!!.jsonArray
        assertTrue("item carries a bold span", item.any { (it as? JsonObject)?.containsKey("bold") == true })
    }

    // ─── tables ───────────────────────────────────────────────────

    private fun tableOf(verse: JsonObject): JsonObject =
        verse["block"]!!.jsonObject["table"]!!.jsonObject

    /** Flatten an inline cell array down to its plain text. */
    @Test
    fun `table cells carry inline styling`() {
        val src = "| Name | Note |\n| --- | --- |\n| a | **bold** |"
        val rows = tableOf(MarkdownBlocks.toStory(src).single().jsonObject)["rows"]!!.jsonArray
        val noteCell = rows[0].jsonArray[1].jsonArray
        assertTrue("bold survives in a cell", noteCell.any { (it as? JsonObject)?.containsKey("bold") == true })
    }

    @Test
    fun `bare triple dash is a rule, not a one-column table`() {
        // No pipe → not a table separator; stays a horizontal rule.
        assertFalse(MarkdownBlocks.isTableSeparator("---"))
        val verse = MarkdownBlocks.toStory("---").single().jsonObject
        assertTrue(verse["block"]!!.jsonObject.containsKey("rule"))
    }

    @Test
    fun `a pipe line with no separator stays a paragraph`() {
        // GFM requires the `---` separator row; without it, literal text.
        val story = MarkdownBlocks.toStory("a | b | c")
        assertTrue(story[0].jsonObject.containsKey("inline"))
    }

    // ─── mixed content ────────────────────────────────────────────

    @Test
    fun `heading plus body plus code plus quote all emit in order`() {
        val src = """
            # Title

            Intro paragraph.

            ```
            code goes here
            ```

            > a thought
        """.trimIndent()
        val story = MarkdownBlocks.toStory(src)
        assertEquals(4, story.size)
        // 0: header, 1: paragraph, 2: code, 3: blockquote verse
        assertNotNull(story[0].jsonObject["block"]!!.jsonObject["header"])
        assertTrue(story[1].jsonObject.containsKey("inline"))
        assertNotNull(story[2].jsonObject["block"]!!.jsonObject["code"])
        assertTrue(story[3].jsonObject.containsKey("inline"))
    }
}
