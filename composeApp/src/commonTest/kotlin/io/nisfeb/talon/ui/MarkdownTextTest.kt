package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pins the block-level Markdown split — the fiddly part (fenced-code
 *  state machine, header/bullet detection, paragraph joining). */
class MarkdownTextTest {

    @Test
    fun `fenced code block is one Code block, language tag dropped`() {
        val blocks = parseMarkdownBlocks("before\n```kotlin\nval x = 1\nval y = 2\n```\nafter")
        assertEquals(
            listOf(
                MdBlock.Paragraph("before"),
                MdBlock.Code("val x = 1\nval y = 2"),
                MdBlock.Paragraph("after"),
            ),
            blocks,
        )
    }

    @Test
    fun `headers and bullets`() {
        val blocks = parseMarkdownBlocks("# Title\n## Sub\n- one\n* two")
        assertEquals(
            listOf(
                MdBlock.Heading(1, "Title"),
                MdBlock.Heading(2, "Sub"),
                MdBlock.Bullet("one"),
                MdBlock.Bullet("two"),
            ),
            blocks,
        )
    }

    @Test
    fun `consecutive non-blank lines join into one paragraph, blank splits`() {
        val blocks = parseMarkdownBlocks("line one\nline two\n\nsecond para")
        assertEquals(
            listOf(
                MdBlock.Paragraph("line one line two"),
                MdBlock.Paragraph("second para"),
            ),
            blocks,
        )
    }

    @Test
    fun `unterminated fence still yields a code block`() {
        val blocks = parseMarkdownBlocks("```\nno close")
        assertEquals(1, blocks.size)
        assertTrue(blocks.single() is MdBlock.Code)
        assertEquals("no close", (blocks.single() as MdBlock.Code).text)
    }
}
