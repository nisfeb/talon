package io.nisfeb.talon.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
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

    @Test
    fun `inline links and italics lose their markers, bare brackets survive`() {
        val a = inlineAnnotated("see [docs](https://x.dev) and *this*", Color.Unspecified)
        assertEquals("see docs and this", a.text)
        assertEquals(
            listOf("https://x.dev"),
            a.getLinkAnnotations(0, a.length).mapNotNull { (it.item as? LinkAnnotation.Url)?.url },
        )
        // Not a link: no `](`. The text must come through untouched.
        assertEquals("[wat] a * b", inlineAnnotated("[wat] a * b", Color.Unspecified).text)
    }

    @Test
    fun `a bare bracketed token before a real link stays literal`() {
        // Regression: '[' used to pair with the first '](' anywhere later,
        // so "[1] see [docs](url)" rendered one link labeled "1] see [docs".
        val a = inlineAnnotated("[1] see [docs](https://x.dev)", Color.Unspecified)
        assertEquals("[1] see docs", a.text)
        assertEquals(
            listOf("https://x.dev"),
            a.getLinkAnnotations(0, a.length).mapNotNull { (it.item as? LinkAnnotation.Url)?.url },
        )
    }

    @Test
    fun `a bracketed label still links`() {
        // Regression of the regression fix: ending the label at the FIRST
        // ']' broke the [[1]](url) citation shape LLMs emit constantly.
        val a = inlineAnnotated("[[1]](https://x.dev) and [a [b] c](https://y.dev)", Color.Unspecified)
        assertEquals("[1] and a [b] c", a.text)
        assertEquals(
            listOf("https://x.dev", "https://y.dev"),
            a.getLinkAnnotations(0, a.length).mapNotNull { (it.item as? LinkAnnotation.Url)?.url },
        )
    }

    @Test
    fun `spaced asterisks stay literal, hugging ones italicize`() {
        // Regression: any two bare stars used to pair, so "2 * 3 * 4"
        // rendered "2  3  4" with " 3 " italic.
        assertEquals("compute 2 * 3 * 4", inlineAnnotated("compute 2 * 3 * 4", Color.Unspecified).text)
        assertEquals("a b c", inlineAnnotated("a *b* c", Color.Unspecified).text)
    }

    @Test
    fun `a numbered list after a bulleted one stays a list`() {
        // Reported from the app: bullets followed by a numbered list
        // rendered the numbers as one run-on line. There was no ordered
        // case at all, so those lines hit the paragraph branch, which
        // joins consecutive lines with spaces.
        val blocks = parseMarkdownBlocks("- one\n- two\n\n1. first\n2. second")
        val bullets = blocks.filterIsInstance<MdBlock.Bullet>().map { it.text }
        val ordered = blocks.filterIsInstance<MdBlock.Ordered>()
        assertEquals(listOf("one", "two"), bullets)
        assertEquals(listOf("first", "second"), ordered.map { it.text })
        assertEquals(listOf(1, 2), ordered.map { it.number })
        // The give-away symptom: nothing may survive as a merged paragraph.
        assertEquals(
            emptyList(),
            blocks.filterIsInstance<MdBlock.Paragraph>().map { it.text },
        )
    }

    @Test
    fun `ordered items keep the author's numbers`() {
        val blocks = parseMarkdownBlocks("3. three\n4. four")
        assertEquals(listOf(3, 4), blocks.filterIsInstance<MdBlock.Ordered>().map { it.number })
    }

    @Test
    fun `paren style numbering works too`() {
        val blocks = parseMarkdownBlocks("1) alpha\n2) beta")
        assertEquals(listOf("alpha", "beta"), blocks.filterIsInstance<MdBlock.Ordered>().map { it.text })
    }

    @Test
    fun `a decimal in prose is not a list`() {
        // "3.14 is pi" must stay a paragraph — the pattern needs
        // whitespace after the separator.
        val blocks = parseMarkdownBlocks("3.14 is pi")
        assertEquals(emptyList(), blocks.filterIsInstance<MdBlock.Ordered>())
        assertEquals(listOf("3.14 is pi"), blocks.filterIsInstance<MdBlock.Paragraph>().map { it.text })
    }
}
