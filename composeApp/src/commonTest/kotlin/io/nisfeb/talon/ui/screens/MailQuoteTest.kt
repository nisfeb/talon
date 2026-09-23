package io.nisfeb.talon.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Quoting lines into a reply, and reading quotes back out of a message. */
class MailQuoteTest {
    private val body = "first line\nsecond line\nthird line"

    @Test fun `a selection quotes the whole lines it touches`() {
        // "cond li" sits inside the second line only.
        assertEquals("second line", wholeLines(body, 14, 21))
        // From partway through the first line to partway through the second.
        assertEquals("first line\nsecond line", wholeLines(body, 3, 14))
        assertEquals("third line", wholeLines(body, body.length - 2, body.length))
    }

    @Test fun `a quote goes after what is written, each line marked`() {
        assertEquals("> a\n> b\n\n", quoteInto("", "a\nb"))
        assertEquals("thanks\n\n> a\n\n", quoteInto("thanks  ", "a"))
        assertEquals("> a\n>\n> b\n\n", quoteInto("", "a\n\nb"))
    }

    @Test fun `a message reads back as its own words and its quotes`() {
        val m = "> what you said\n> and more\nmy answer\n\n> another bit\nand to that"
        assertEquals(
            listOf(true to "what you said\nand more", false to "my answer", true to "another bit", false to "and to that"),
            quoteBlocks(m),
        )
        assertEquals(listOf(false to "no quotes here"), quoteBlocks("no quotes here"))
    }

    @Test fun `a label is found by its letters in order`() {
        assertTrue(fuzzyHas("invoices", "inv"))
        assertTrue(fuzzyHas("in review", "inr"))
        assertFalse(fuzzyHas("invoices", "vni"))
    }

    @Test fun `a tree node says the message's own words`() {
        assertEquals("my answer and more", treePreview("> quoted\n\nmy answer\nand more"))
        assertEquals("(no text)", treePreview("> only a quote"))
    }
}
