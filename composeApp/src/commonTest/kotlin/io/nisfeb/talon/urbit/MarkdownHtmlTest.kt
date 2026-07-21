package io.nisfeb.talon.urbit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * This output is served unauthenticated from the author's own ship, so
 * the escaping cases below are a security boundary rather than
 * formatting preferences. A note is arbitrary text written by whoever
 * can edit the notebook.
 */
class MarkdownHtmlTest {

    // ---- injection ------------------------------------------------------

    @Test
    fun `raw html in a note is escaped, not passed through`() {
        val html = MarkdownHtml.render("<script>alert('xss')</script>")
        assertFalse(html.contains("<script>"), "a script tag must never reach the page")
        assertTrue(html.contains("&lt;script&gt;"))
    }

    @Test
    fun `html inside a code fence stays inert`() {
        val html = MarkdownHtml.render("```\n<img src=x onerror=alert(1)>\n```")
        assertFalse(html.contains("<img"))
        assertTrue(html.contains("&lt;img"))
    }

    @Test
    fun `javascript link targets are defused`() {
        val html = MarkdownHtml.render("[click me](javascript:alert(1))")
        assertFalse(html.contains("javascript:"), "a live javascript: href is script execution")
        assertTrue(html.contains("href=\"#\""))
        // The label still shows; we drop the target, not the content.
        assertTrue(html.contains("click me"))
    }

    @Test
    fun `data and vbscript targets are defused too`() {
        assertEquals("#", MarkdownHtml.safeHref("data:text/html;base64,PHNjcmlwdD4="))
        assertEquals("#", MarkdownHtml.safeHref("vbscript:msgbox(1)"))
        assertEquals("#", MarkdownHtml.safeHref("JaVaScRiPt:alert(1)"))
    }

    @Test
    fun `ordinary links survive`() {
        assertEquals("https://example.com/x", MarkdownHtml.safeHref("https://example.com/x"))
        assertEquals("http://example.com", MarkdownHtml.safeHref("http://example.com"))
        assertEquals("mailto:a@b.c", MarkdownHtml.safeHref("mailto:a@b.c"))
        assertEquals("/relative/path", MarkdownHtml.safeHref("/relative/path"))
    }

    @Test
    fun `quotes in text cannot break out of an attribute`() {
        val html = MarkdownHtml.render("""[x]("onmouseover="alert(1))""")
        assertFalse(html.contains("onmouseover=\"alert"), "must not escape the href attribute")
    }

    // ---- block structure -------------------------------------------------

    @Test
    fun `headings render at their level`() {
        assertTrue(MarkdownHtml.render("# Title").contains("<h1>Title</h1>"))
        assertTrue(MarkdownHtml.render("### Sub").contains("<h3>Sub</h3>"))
        // More than six hashes clamps rather than emitting <h9>.
        assertTrue(MarkdownHtml.render("######## Deep").contains("<h6>"))
    }

    @Test
    fun `paragraphs split on blank lines`() {
        val html = MarkdownHtml.render("one\n\ntwo")
        assertTrue(html.contains("<p>one</p>"))
        assertTrue(html.contains("<p>two</p>"))
    }

    @Test
    fun `bullet and ordered lists render`() {
        val ul = MarkdownHtml.render("- a\n- b")
        assertTrue(ul.contains("<ul>") && ul.contains("<li>a</li>") && ul.contains("<li>b</li>"))
        val ol = MarkdownHtml.render("1. first\n2. second")
        assertTrue(ol.contains("<ol>") && ol.contains("<li>first</li>"))
    }

    @Test
    fun `blockquotes render`() {
        assertTrue(MarkdownHtml.render("> quoted").contains("<blockquote>"))
    }

    // ---- inline ----------------------------------------------------------

    @Test
    fun `inline emphasis and code render`() {
        val html = MarkdownHtml.render("**bold** and *italic* and `code` and ~~gone~~")
        assertTrue(html.contains("<strong>bold</strong>"))
        assertTrue(html.contains("<em>italic</em>"))
        assertTrue(html.contains("<code>code</code>"))
        assertTrue(html.contains("<del>gone</del>"))
    }

    @Test
    fun `emphasis markers inside code stay literal`() {
        val html = MarkdownHtml.render("`**not bold**`")
        assertTrue(html.contains("<code>**not bold**</code>"))
        assertFalse(html.contains("<strong>"))
    }

    @Test
    fun `underscores in a url are not read as emphasis`() {
        // Found by the code-span test: emphasis used to run over the
        // whole string after links were built, so a path like a_b_c
        // grew an <em> in the middle and the link broke.
        val html = MarkdownHtml.render("[docs](https://example.com/a_b_c_d)")
        assertTrue(html.contains("https://example.com/a_b_c_d"), "url must survive intact")
        assertFalse(html.contains("<em>"))
    }

    @Test
    fun `a note cannot forge a placeholder`() {
        // The held-fragment markers are control characters; content
        // carrying one must not be able to re-inject a fragment.
        val html = MarkdownHtml.render("0 plain `code`")
        assertTrue(html.contains("<code>code</code>"))
        assertFalse(html.contains("\u0001"))
    }

    @Test
    fun `the live codex note renders`() {
        // Verbatim from ~minder-folden/codex-7, the first real note.
        val html = MarkdownHtml.render(
            "I'm excited to see how this new channel type (that's " +
                "**markdown friendly**) will find its legs.\n\n" +
                "Feel free to post, share; whatever.",
        )
        assertTrue(html.contains("<strong>markdown friendly</strong>"))
        assertTrue(html.contains("<p>"))
        // Apostrophes get escaped, never left to break an attribute.
        assertFalse(html.contains("I'm excited"))
        assertTrue(html.contains("I&#39;m excited"))
    }

    @Test
    fun `unrecognized markup survives as text rather than vanishing`() {
        val html = MarkdownHtml.render("| a | b |\n| - | - |")
        assertTrue(html.contains("a") && html.contains("b"), "content must never be dropped")
    }

    @Test
    fun `published lists match what the app displays`() {
        // The in-app parser accepts `1)` as well as `1.`; if this one
        // didn't, a list would render on screen and flatten on the web.
        val dot = MarkdownHtml.render("1. first\n2. second")
        assertTrue(dot.contains("<ol>") && dot.contains("<li>first</li>"))
        val paren = MarkdownHtml.render("1) alpha\n2) beta")
        assertTrue(paren.contains("<ol>") && paren.contains("<li>alpha</li>"), "paren numbering must list too")
        // And a decimal in prose is still prose.
        assertFalse(MarkdownHtml.render("3.14 is pi").contains("<ol>"))
    }
}
