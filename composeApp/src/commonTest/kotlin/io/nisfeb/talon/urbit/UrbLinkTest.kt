package io.nisfeb.talon.urbit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UrbLinkTest {

    @Test
    fun isUrbUrlAcceptsWellFormed() {
        assertTrue(UrbLink.isUrbUrl("urb://~sampel-palnet/notes/hello.gmi"))
        assertTrue(UrbLink.isUrbUrl("urb://~zod/"))
        assertTrue(UrbLink.isUrbUrl("  urb://~zod/a/b  ")) // trimmed
    }

    @Test
    fun isUrbUrlRejectsJunk() {
        assertFalse(UrbLink.isUrbUrl("urb://"))            // scheme only
        assertFalse(UrbLink.isUrbUrl("https://example.com"))
        assertFalse(UrbLink.isUrbUrl("urb://~zod a"))      // embedded space
        assertFalse(UrbLink.isUrbUrl("see urb://~zod/x"))  // surrounding text
    }

    @Test
    fun extractsBareUrlFromText() {
        val text = "check out urb://~sampel-palnet/page.gmi it's great"
        assertEquals(listOf("urb://~sampel-palnet/page.gmi"), UrbLink.extract(text))
    }

    @Test
    fun extractsMultiple() {
        val text = "urb://~zod/a and urb://~nec/b"
        assertEquals(listOf("urb://~zod/a", "urb://~nec/b"), UrbLink.extract(text))
    }

    @Test
    fun trimsTrailingSentencePunctuation() {
        assertEquals(listOf("urb://~zod/page"), UrbLink.extract("go to urb://~zod/page."))
        assertEquals(listOf("urb://~zod/page"), UrbLink.extract("urb://~zod/page!"))
        assertEquals(listOf("urb://~zod/a/b"), UrbLink.extract("(urb://~zod/a/b),"))
    }

    @Test
    fun keepsBalancedParens() {
        // A closing paren that pairs with one inside the URL stays.
        assertEquals(
            listOf("urb://~zod/a(b)"),
            UrbLink.extract("urb://~zod/a(b)"),
        )
        // A closing paren with no opener inside the URL is trimmed
        // (it's the wrapping prose paren).
        assertEquals(
            listOf("urb://~zod/a"),
            UrbLink.extract("(urb://~zod/a)"),
        )
    }

    @Test
    fun excludesQuotesAndAngles() {
        assertEquals(listOf("urb://~zod/a"), UrbLink.extract("\"urb://~zod/a\""))
        assertEquals(listOf("urb://~zod/a"), UrbLink.extract("<urb://~zod/a>"))
    }

    @Test
    fun noFalsePositives() {
        assertTrue(UrbLink.extract("just some text, no links").isEmpty())
        assertTrue(UrbLink.extract("https://example.com/urb").isEmpty())
        assertTrue(UrbLink.extract("urb:// ").isEmpty()) // scheme only, then space
    }

    @Test
    fun rangesPointIntoOriginal() {
        val text = "x urb://~zod/p y"
        val ranges = UrbLink.findRanges(text)
        assertEquals(1, ranges.size)
        val r = ranges[0]
        assertEquals("urb://~zod/p", text.substring(r.first, r.last + 1))
    }
}
