package io.nisfeb.talon.urbit

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * Guards against regressions in the inline tokenizer. Covers only the
 * common shapes our composer produces; exotic edge cases (overlapping
 * markers, escapes) intentionally fall through as plain text.
 */
class MarkdownTest {

    // The `assertEquals(n, out.size)` guards below were added after
    // mutation-testing showed that off-by-one index bumps in the
    // tokenizer (`i + 1` → `i + 0`) slipped extra trailing tokens
    // past tests that only checked out[0] / out[1]. Exact sizes lock
    // the tokenizer end-to-end.

    @Test
    fun `urb link stays one autolink and does not split out the inner patp`() {
        // A `urb://~ship/path` link (e.g. shared from Lattice) must autolink
        // whole — the `~ship` inside it must NOT be grabbed as a ship mention,
        // which would leave only the patp clickable.
        val out = Markdown.parseInlines("see urb://~sampel-palnet/notes/x here")
        assertEquals(3, out.size)
        val link = out[1].jsonObject["link"]!!.jsonObject
        assertEquals("urb://~sampel-palnet/notes/x", link["href"]!!.jsonPrimitive.content)
        assertEquals("urb://~sampel-palnet/notes/x", link["content"]!!.jsonPrimitive.content)
        assertEquals("see ", (out[0] as JsonPrimitive).content)
        assertEquals(" here", (out[2] as JsonPrimitive).content)
        // No ship span anywhere in the output.
        val hasShip = out.any { (it as? JsonObject)?.keys?.contains("ship") == true }
        assertEquals(false, hasShip)
    }

    // URL-end parsing — mutation-tester surfaced case-insensitivity
    // and trailing-punctuation-strip paths as gaps. These lock them.

    @Test
    fun `autolink recognizes uppercase http and https schemes`() {
        // One test per scheme so both `regionMatches` ignoreCase
        // arguments get exercised — mutation-testing showed a single
        // https test left the http branch's ignoreCase untested.
        val outHttps = Markdown.parseInlines("visit HTTPS://example.com now")
        assertEquals(3, outHttps.size)
        val httpsLink = outHttps[1].jsonObject["link"]!!.jsonObject
        assertEquals("HTTPS://example.com", httpsLink["href"]!!.jsonPrimitive.content)

        val outHttp = Markdown.parseInlines("visit HTTP://example.com now")
        assertEquals(3, outHttp.size)
        val httpLink = outHttp[1].jsonObject["link"]!!.jsonObject
        assertEquals("HTTP://example.com", httpLink["href"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tilde pressed against a word character does not start a patp`() {
        // "foo~sampel" is plain text, not a ship reference. Catches a
        // word-boundary mutation in the tokenizer's patp gate.
        val out = Markdown.parseInlines("foo~sampel-palnet bar")
        // No Ship span in the output — everything is plain.
        val hasShip = out.any {
            (it as? JsonObject)?.keys?.contains("ship") == true
        }
        assertEquals(false, hasShip)
    }

    @Test
    fun `autolink strips a trailing period that belongs to the sentence`() {
        val out = Markdown.parseInlines("see https://example.com.")
        // "see ", link(example.com), "."  — dot is not part of the URL.
        assertEquals(3, out.size)
        val link = out[1].jsonObject["link"]!!.jsonObject
        assertEquals("https://example.com", link["href"]!!.jsonPrimitive.content)
        assertEquals(".", (out[2] as JsonPrimitive).content)
    }

    @Test
    fun `autolink strips an unbalanced trailing paren`() {
        // Matches e.g. "(see https://example.com)". The close paren is
        // parenthetical prose, not URL payload.
        val out = Markdown.parseInlines("(see https://example.com)")
        val link = out[1].jsonObject["link"]!!.jsonObject
        assertEquals("https://example.com", link["href"]!!.jsonPrimitive.content)
        val tail = out.last() as JsonPrimitive
        assertTrue(
            tail.content.contains(")"),
            "trailing plain should carry the paren: ${tail.content}",
        )
    }

    @Test
    fun `autolink keeps balanced parens inside the URL`() {
        // Wikipedia-style URLs: the closing paren is paired, so don't
        // strip it.
        val out = Markdown.parseInlines(
            "see https://en.wikipedia.org/wiki/Foo_(bar) for more"
        )
        val link = out[1].jsonObject["link"]!!.jsonObject
        assertEquals(
            "https://en.wikipedia.org/wiki/Foo_(bar)",
            link["href"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `unclosed bold passes through as plain text`() {
        val out = Markdown.parseInlines("**oops")
        // No closing `**` → everything is plain.
        assertEquals(1, out.size)
        val prim = out[0] as JsonPrimitive
        assertEquals("**oops", prim.content)
    }

    @Test
    fun `partial link-shape falls through`() {
        // `[label]` without `(url)` shouldn't try to be a link.
        val out = Markdown.parseInlines("say [hi] there")
        assertTrue(out.all { it is JsonPrimitive }, "$out")
        assertEquals("say [hi] there", out.joinToString("") { it.jsonPrimitive.content })
    }

    @Test
    fun `only a real ship becomes a mention`() {
        // `~abcdef` has the shape of a planet but not the syllables; the
        // ship's parser rejects it, so it must stay text.
        val out = Markdown.parseInlines("hey ~abcdef and ~sampel-palnet and ~zod")
        assertEquals("hey ~abcdef and ", (out[0] as JsonPrimitive).content)
        assertEquals("~sampel-palnet", out[1].jsonObject["ship"]!!.jsonPrimitive.content)
        assertEquals("~zod", out[3].jsonObject["ship"]!!.jsonPrimitive.content)
        assertTrue(isValidPatp("~satnet-rinsyr-silsul-bacnec--todmeb-harwen-fadpem-ribdyr"))
        assertFalse(isValidPatp("~abcdef"))
        assertTrue(isValidPatp("~wisper"))
        assertFalse(isValidPatp("~zodzod"))
    }
}
