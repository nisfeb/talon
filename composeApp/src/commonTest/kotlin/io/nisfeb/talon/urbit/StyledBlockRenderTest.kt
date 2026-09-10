package io.nisfeb.talon.urbit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A block construct must survive inline styling inside it. The
 * composer turns `**a**` into a span at send time, and the renderer
 * used to expand block markdown only for verses that were purely
 * plain text — so one bold cell silently reduced a whole table to
 * literal pipes, which is what a user hit with a table of callsigns.
 */
class StyledBlockRenderTest {
    private fun parts(md: String): List<StoryPart> = Story.parse(chatTextToStory(md))
    private fun kinds(md: String) = parts(md).map { it::class.simpleName }

    @Test
    fun `a table renders with a bold cell, as it does without one`() {
        val bold = """
            | Team | Callsign |
            |------|----------|
            | **MB1I** (EU #6) | E77DX |
        """.trimIndent()
        val plain = bold.replace("**", "")
        assertEquals(listOf("Table"), kinds(plain), "the plain table was already fine")
        assertEquals(listOf("Table"), kinds(bold), "a bold cell must not flatten the table")
        val table = parts(bold).first() as StoryPart.Table
        assertEquals(listOf("Team", "Callsign"), table.header.map { it.text })
        // The styling survives into the cell rather than being dropped.
        assertTrue(table.rows[0][0].text.contains("MB1I"), "cell text: ${table.rows[0][0].text}")
    }

    @Test
    fun `a list keeps its shape when an item is styled`() {
        assertEquals(kinds("- a item\n- b item"), kinds("- **a** item\n- b item"))
    }

    @Test
    fun `prose with styling is untouched`() {
        assertEquals(listOf("Text"), kinds("just **bold** prose, no block here"))
    }

    @Test
    fun `an already structured quote is not re-derived from its own rendering`() {
        // A blockquote span is block-level, so the verse must not be
        // rebuilt and re-parsed; it renders straight through.
        val story = Story.parse(
            kotlinx.serialization.json.Json.parseToJsonElement(
                """[{"inline":[{"blockquote":["quoted"]}]}]""",
            ),
        )
        assertEquals(listOf("Text"), story.map { it::class.simpleName })
    }
}
