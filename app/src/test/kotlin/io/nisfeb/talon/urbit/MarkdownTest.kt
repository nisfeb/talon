package io.nisfeb.talon.urbit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards against regressions in the inline tokenizer. Covers only the
 * common shapes our composer produces; exotic edge cases (overlapping
 * markers, escapes) intentionally fall through as plain text.
 */
class MarkdownTest {

    private fun firstKey(el: JsonObject): String = el.keys.first()

    @Test
    fun `plain text passes through as a primitive`() {
        val out = Markdown.parseInlines("hello world")
        assertEquals(1, out.size)
        val prim = out[0] as JsonPrimitive
        assertEquals("hello world", prim.content)
    }

    // The `assertEquals(n, out.size)` guards below were added after
    // mutation-testing showed that off-by-one index bumps in the
    // tokenizer (`i + 1` → `i + 0`) slipped extra trailing tokens
    // past tests that only checked out[0] / out[1]. Exact sizes lock
    // the tokenizer end-to-end.

    @Test
    fun `bold wraps content in bold span`() {
        val out = Markdown.parseInlines("**loud**")
        assertEquals(1, out.size)
        val span = out[0].jsonObject
        assertEquals("bold", firstKey(span))
        val inner = span["bold"] as JsonArray
        assertEquals(1, inner.size)
        assertEquals("loud", (inner[0] as JsonPrimitive).content)
    }

    @Test
    fun `italic with star`() {
        val out = Markdown.parseInlines("*slanted*")
        assertEquals(1, out.size)
        assertEquals("italics", firstKey(out[0].jsonObject))
    }

    @Test
    fun `italic with underscore`() {
        val out = Markdown.parseInlines("_slanted_")
        assertEquals(1, out.size)
        assertEquals("italics", firstKey(out[0].jsonObject))
    }

    @Test
    fun `strikethrough with double tilde`() {
        val out = Markdown.parseInlines("~~gone~~")
        assertEquals(1, out.size)
        assertEquals("strike", firstKey(out[0].jsonObject))
    }

    @Test
    fun `inline code`() {
        val out = Markdown.parseInlines("`x = 1`")
        assertEquals(1, out.size)
        val span = out[0].jsonObject
        assertEquals("code", firstKey(span))
        assertEquals("x = 1", span["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `link with label`() {
        val out = Markdown.parseInlines("see [here](https://example.com) for more")
        // "see ", link, " for more".
        assertEquals(3, out.size)
        val link = out[1].jsonObject["link"]!!.jsonObject
        assertEquals("https://example.com", link["href"]!!.jsonPrimitive.content)
        assertEquals("here", link["content"]!!.jsonPrimitive.content)
        assertEquals("see ", (out[0] as JsonPrimitive).content)
        assertEquals(" for more", (out[2] as JsonPrimitive).content)
    }

    @Test
    fun `bare url becomes an autolink`() {
        val out = Markdown.parseInlines("go https://example.com now")
        assertEquals(3, out.size)
        val link = out[1].jsonObject["link"]!!.jsonObject
        // Bare URL: label == href.
        assertEquals("https://example.com", link["href"]!!.jsonPrimitive.content)
        assertEquals("https://example.com", link["content"]!!.jsonPrimitive.content)
        assertEquals("go ", (out[0] as JsonPrimitive).content)
        assertEquals(" now", (out[2] as JsonPrimitive).content)
    }

    @Test
    fun `patp reference produces ship span`() {
        val out = Markdown.parseInlines("hi ~sampel-palnet friend")
        // Expect: "hi ", ship(~sampel-palnet), " friend".
        assertEquals(3, out.size)
        val ship = out[1].jsonObject
        assertEquals("ship", firstKey(ship))
        assertEquals("~sampel-palnet", ship["ship"]!!.jsonPrimitive.content)
        assertEquals("hi ", (out[0] as JsonPrimitive).content)
        assertEquals(" friend", (out[2] as JsonPrimitive).content)
    }

    @Test
    fun `long-form patp with dashes recognized`() {
        val out = Markdown.parseInlines("~ricsul-bilwyt-dozzod-nisfeb says hi")
        // Leading patp + trailing plain text.
        assertEquals(2, out.size)
        val ship = out[0].jsonObject
        assertEquals("ship", firstKey(ship))
        assertEquals("~ricsul-bilwyt-dozzod-nisfeb", ship["ship"]!!.jsonPrimitive.content)
        assertEquals(" says hi", (out[1] as JsonPrimitive).content)
    }

    @Test
    fun `mixed bold and plain`() {
        val out = Markdown.parseInlines("hello **brave** world")
        // [plain "hello ", bold["brave"], plain " world"]
        assertEquals(3, out.size)
        assertTrue(out[0] is JsonPrimitive)
        assertEquals("bold", firstKey(out[1].jsonObject))
        assertTrue(out[2] is JsonPrimitive)
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
        assertTrue("trailing plain should carry the paren: ${tail.content}",
            tail.content.contains(")"))
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
        // At least doesn't explode — output is some sequence of primitives.
        assertTrue(out.size >= 1)
    }
}
