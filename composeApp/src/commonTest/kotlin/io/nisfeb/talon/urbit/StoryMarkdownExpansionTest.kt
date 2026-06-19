package io.nisfeb.talon.urbit

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Render-time markdown: a plain-text span (the shape a bot or a foreign
 * client posts when it never structured its content) must still render
 * styled. Exercises [Story.parse]'s expansion of plain verses through the
 * inline + block parsers across every channel — they all funnel here.
 */
class StoryMarkdownExpansionTest {

    /** One verse holding a single raw plain-text string — i.e. exactly
     *  what an unstyled bot message looks like on the wire. */
    private fun plainStory(text: String): JsonArray = buildJsonArray {
        add(buildJsonObject { put("inline", buildJsonArray { add(JsonPrimitive(text)) }) })
    }

    private fun firstText(parts: List<StoryPart>): StoryPart.Text =
        parts.first { it is StoryPart.Text } as StoryPart.Text

    private fun hasBold(p: StoryPart.Text) =
        p.text.spanStyles.any { it.item.fontWeight == FontWeight.Bold }

    private fun hasItalic(p: StoryPart.Text) =
        p.text.spanStyles.any { it.item.fontStyle == FontStyle.Italic }

    // ─── inline ───────────────────────────────────────────────────

    @Test
    fun `raw bold renders a bold span and drops the markers`() {
        val t = firstText(Story.parse(plainStory("see **this**")))
        assertEquals("see this", t.text.text)
        assertTrue(hasBold(t))
    }

    @Test
    fun `raw italic renders an italic span`() {
        val t = firstText(Story.parse(plainStory("an _emphasis_ word")))
        assertEquals("an emphasis word", t.text.text)
        assertTrue(hasItalic(t))
    }

    @Test
    fun `inline code drops the backticks`() {
        val t = firstText(Story.parse(plainStory("run `cmd` now")))
        assertEquals("run cmd now", t.text.text)
    }

    @Test
    fun `inline markdown renders even in a mixed verse with a link span`() {
        // Tlon's server wraps bare URLs in a link block, leaving the rest
        // of the message as a plain span — a mixed verse. The bold in that
        // plain span must still render.
        val story = buildJsonArray {
            add(buildJsonObject {
                put("inline", buildJsonArray {
                    add(JsonPrimitive("see **bold** at "))
                    add(buildJsonObject {
                        put("link", buildJsonObject {
                            put("href", "https://example.com")
                            put("content", "example.com")
                        })
                    })
                })
            })
        }
        val t = firstText(Story.parse(story))
        assertTrue(hasBold(t))
    }

    @Test
    fun `a lone asterisk is left literal`() {
        // No closing marker → not italic; the text survives verbatim.
        val t = firstText(Story.parse(plainStory("2 * 3 = 6")))
        assertEquals("2 * 3 = 6", t.text.text)
        assertFalse(hasItalic(t))
    }

    // ─── block ────────────────────────────────────────────────────

    @Test
    fun `a heading line renders bold heading text`() {
        val t = firstText(Story.parse(plainStory("# Title")))
        assertEquals("Title", t.text.text)
        assertTrue(hasBold(t))
    }

    @Test
    fun `bullet lines render as a bulleted block`() {
        val t = firstText(Story.parse(plainStory("- a\n- b")))
        assertTrue(t.text.text.contains("• a"))
        assertTrue(t.text.text.contains("• b"))
    }

    @Test
    fun `numbered lines render with their numbers`() {
        val t = firstText(Story.parse(plainStory("1. first\n2. second")))
        assertTrue(t.text.text.contains("1. first"))
        assertTrue(t.text.text.contains("2. second"))
    }

    @Test
    fun `a fenced code block becomes a Code part`() {
        val parts = Story.parse(plainStory("```\nx = 1\n```"))
        val code = parts.first { it is StoryPart.Code } as StoryPart.Code
        assertEquals("x = 1", code.code)
    }

    @Test
    fun `a GFM table renders as a Table part with header and rows`() {
        val src = "| Header 1 | Header 2 |\n| --- | --- |\n| Cell 1 | Cell 2 |"
        val parts = Story.parse(plainStory(src))
        val table = parts.first { it is StoryPart.Table } as StoryPart.Table
        assertEquals(listOf("Header 1", "Header 2"), table.header.map { it.text })
        assertEquals(1, table.rows.size)
        assertEquals(listOf("Cell 1", "Cell 2"), table.rows[0].map { it.text })
    }

    @Test
    fun `a blockquote line keeps its text`() {
        val t = firstText(Story.parse(plainStory("> quoted")))
        assertTrue(t.text.text.contains("quoted"))
    }

    @Test
    fun `a horizontal rule renders a divider, not a literal tag`() {
        val t = firstText(Story.parse(plainStory("---")))
        assertTrue(t.text.text.contains("─"))
        assertFalse(t.text.text.contains("[rule]"))
    }

    // ─── task lists ───────────────────────────────────────────────

    @Test
    fun `task list items render as checkbox glyphs`() {
        val t = firstText(Story.parse(plainStory("- [ ] todo\n- [x] done")))
        assertTrue(t.text.text.contains("☐ todo"))
        assertTrue(t.text.text.contains("☑ done"))
        // The literal `[ ]` / `[x]` marker is stripped.
        assertFalse(t.text.text.contains("[ ]"))
        assertFalse(t.text.text.contains("[x]"))
    }

    @Test
    fun `uppercase X also marks a task done`() {
        val t = firstText(Story.parse(plainStory("- [X] done")))
        assertTrue(t.text.text.contains("☑ done"))
    }

    // ─── emoji shortcodes ─────────────────────────────────────────

    @Test
    fun `known shortcode becomes its glyph`() {
        val t = firstText(Story.parse(plainStory("nice :tada: party")))
        assertTrue(t.text.text.contains("🎉"))
        assertFalse(t.text.text.contains(":tada:"))
    }

    @Test
    fun `unknown shortcode is left literal`() {
        val t = firstText(Story.parse(plainStory("look :notarealemoji: ok")))
        assertTrue(t.text.text.contains(":notarealemoji:"))
    }

    @Test
    fun `shortcode inside inline code is not substituted`() {
        // Code spans are literal — `:tada:` in backticks stays text.
        val t = firstText(Story.parse(plainStory("type `:tada:` literally")))
        assertTrue(t.text.text.contains(":tada:"))
        assertFalse(t.text.text.contains("🎉"))
    }

    // ─── guards ───────────────────────────────────────────────────

    @Test
    fun `plain prose is unchanged`() {
        val t = firstText(Story.parse(plainStory("just a normal sentence")))
        assertEquals("just a normal sentence", t.text.text)
    }

    @Test
    fun `expansion off leaves markdown literal`() {
        val t = firstText(Story.parse(plainStory("see **this**"), expandMarkdown = false))
        assertEquals("see **this**", t.text.text)
        assertFalse(hasBold(t))
    }

    @Test
    fun `junk input yields no parts`() {
        assertEquals(emptyList<StoryPart>(), Story.parse(JsonPrimitive("not a story") as JsonElement))
    }
}
