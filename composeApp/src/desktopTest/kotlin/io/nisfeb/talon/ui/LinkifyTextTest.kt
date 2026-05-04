package io.nisfeb.talon.ui

import androidx.compose.ui.text.LinkAnnotation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the URL detection / trailing-punctuation rules so a future
 * regex tweak doesn't silently break clickable links in statuses.
 */
class LinkifyTextTest {

    private fun urlsIn(text: String): List<String> {
        val annotated = linkifyStatus(text)
        return annotated.getLinkAnnotations(0, annotated.length)
            .mapNotNull { (it.item as? LinkAnnotation.Url)?.url }
    }

    @Test
    fun `plain string with no urls returns no link annotations`() {
        assertTrue(urlsIn("just hanging out").isEmpty())
        assertTrue(urlsIn("").isEmpty())
    }

    @Test
    fun `https url is detected`() {
        assertEquals(listOf("https://example.com"), urlsIn("see https://example.com"))
    }

    @Test
    fun `http url is detected`() {
        assertEquals(listOf("http://example.com"), urlsIn("http://example.com"))
    }

    @Test
    fun `bare www gets https prefix in the href`() {
        assertEquals(listOf("https://www.example.com"), urlsIn("at www.example.com please"))
    }

    @Test
    fun `trailing sentence punctuation is dropped from the link`() {
        assertEquals(listOf("https://example.com"), urlsIn("check https://example.com."))
        assertEquals(listOf("https://example.com"), urlsIn("at https://example.com, then"))
        assertEquals(listOf("https://example.com"), urlsIn("https://example.com!"))
        assertEquals(listOf("https://example.com"), urlsIn("https://example.com?"))
    }

    @Test
    fun `parenthesized url drops the closing paren when it's the wrapper not part of the url`() {
        // "see (https://example.com)" — the `)` belongs to the prose.
        assertEquals(listOf("https://example.com"), urlsIn("see (https://example.com)"))
    }

    @Test
    fun `wikipedia-style url with parens in the path keeps them`() {
        // "https://en.wikipedia.org/wiki/Foo_(bar)" — the `)` is part
        // of the path; trimming would break the link.
        assertEquals(
            listOf("https://en.wikipedia.org/wiki/Foo_(bar)"),
            urlsIn("read https://en.wikipedia.org/wiki/Foo_(bar)"),
        )
    }

    @Test
    fun `multiple urls in one status all get annotated`() {
        val urls = urlsIn("see https://a.example and also https://b.example/path?q=1")
        assertEquals(
            listOf("https://a.example", "https://b.example/path?q=1"),
            urls,
        )
    }

    @Test
    fun `linkified text preserves the original characters including the url body`() {
        val src = "check out https://example.com today!"
        val annotated = linkifyStatus(src)
        // The annotation strips the trailing `!` from the href but we
        // still want it visible in the rendered text.
        assertEquals(src, annotated.text)
    }
}
