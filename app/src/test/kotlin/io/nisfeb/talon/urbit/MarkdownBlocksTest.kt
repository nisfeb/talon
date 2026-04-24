package io.nisfeb.talon.urbit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownBlocksTest {

    // ─── paragraphs ────────────────────────────────────────────────

    @Test
    fun `empty input produces empty story`() {
        val story = MarkdownBlocks.toStory("")
        assertEquals(0, story.size)
    }

    @Test
    fun `single paragraph becomes one inline verse`() {
        val story = MarkdownBlocks.toStory("hello world")
        assertEquals(1, story.size)
        val verse = story[0].jsonObject
        assertNotNull("verse has inline", verse["inline"])
        assertNull("no block on an inline verse", verse["block"])
    }

    @Test
    fun `blank lines split paragraphs`() {
        val story = MarkdownBlocks.toStory("one\n\ntwo\n\nthree")
        assertEquals(3, story.size)
        story.forEach { assertTrue("inline verse", it.jsonObject.containsKey("inline")) }
    }

    @Test
    fun `consecutive lines in a paragraph are joined with newline`() {
        val story = MarkdownBlocks.toStory("line one\nline two")
        assertEquals(1, story.size)
    }

    // ─── headings ──────────────────────────────────────────────────

    @Test
    fun `h1 emits a header block with tag h1`() {
        val verse = MarkdownBlocks.toStory("# Title").single().jsonObject
        val header = verse["block"]!!.jsonObject["header"]!!.jsonObject
        assertEquals("h1", header["tag"]!!.jsonPrimitive.content)
    }

    @Test
    fun `h2 emits h2`() {
        val verse = MarkdownBlocks.toStory("## Sub").single().jsonObject
        val header = verse["block"]!!.jsonObject["header"]!!.jsonObject
        assertEquals("h2", header["tag"]!!.jsonPrimitive.content)
    }

    @Test
    fun `h3 emits h3`() {
        val verse = MarkdownBlocks.toStory("### Sub-sub").single().jsonObject
        val header = verse["block"]!!.jsonObject["header"]!!.jsonObject
        assertEquals("h3", header["tag"]!!.jsonPrimitive.content)
    }

    @Test
    fun `hash without space is plain paragraph`() {
        // Tight markdown spec — `#notspace` isn't a heading.
        val verse = MarkdownBlocks.toStory("#notspace").single().jsonObject
        assertTrue(verse.containsKey("inline"))
        assertTrue(!verse.containsKey("block"))
    }

    // ─── code blocks ──────────────────────────────────────────────

    @Test
    fun `fenced code block emits a code block`() {
        val story = MarkdownBlocks.toStory("```\nlet x = 1\n```")
        assertEquals(1, story.size)
        val code = story[0].jsonObject["block"]!!.jsonObject["code"]!!.jsonObject
        assertEquals("let x = 1", code["code"]!!.jsonPrimitive.content)
        assertEquals("", code["lang"]!!.jsonPrimitive.content)
    }

    @Test
    fun `fenced code block with language tag preserves lang`() {
        val story = MarkdownBlocks.toStory("```kotlin\nfun main() {}\n```")
        val code = story[0].jsonObject["block"]!!.jsonObject["code"]!!.jsonObject
        assertEquals("kotlin", code["lang"]!!.jsonPrimitive.content)
        assertEquals("fun main() {}", code["code"]!!.jsonPrimitive.content)
    }

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

    @Test
    fun `code after text flushes paragraph first`() {
        val story = MarkdownBlocks.toStory("before\n\n```\ncode\n```\n\nafter")
        assertEquals(3, story.size)
        assertTrue(story[0].jsonObject.containsKey("inline"))
        val code = story[1].jsonObject["block"]!!.jsonObject["code"]!!.jsonObject
        assertEquals("code", code["code"]!!.jsonPrimitive.content)
        assertTrue(story[2].jsonObject.containsKey("inline"))
    }

    // ─── blockquote ──────────────────────────────────────────────

    @Test
    fun `blockquote emits an inline verse with a blockquote span`() {
        val verse = MarkdownBlocks.toStory("> wisdom").single().jsonObject
        val inline = verse["inline"] as JsonArray
        val span = inline[0].jsonObject
        assertTrue("wraps under blockquote", span.containsKey("blockquote"))
    }

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

    @Test
    fun `triple dash emits a rule block`() {
        val verse = MarkdownBlocks.toStory("---").single().jsonObject
        val block = verse["block"]!!.jsonObject
        assertTrue("has rule key", block.containsKey("rule"))
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
