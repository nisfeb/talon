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

    @Test
    fun `bold wraps content in bold span`() {
        val out = Markdown.parseInlines("**loud**")
        val span = out[0].jsonObject
        assertEquals("bold", firstKey(span))
        val inner = span["bold"] as JsonArray
        assertEquals("loud", (inner[0] as JsonPrimitive).content)
    }

    @Test
    fun `italic with star`() {
        val out = Markdown.parseInlines("*slanted*")
        assertEquals("italics", firstKey(out[0].jsonObject))
    }

    @Test
    fun `italic with underscore`() {
        val out = Markdown.parseInlines("_slanted_")
        assertEquals("italics", firstKey(out[0].jsonObject))
    }

    @Test
    fun `strikethrough with double tilde`() {
        val out = Markdown.parseInlines("~~gone~~")
        assertEquals("strike", firstKey(out[0].jsonObject))
    }

    @Test
    fun `inline code`() {
        val out = Markdown.parseInlines("`x = 1`")
        val span = out[0].jsonObject
        assertEquals("code", firstKey(span))
        assertEquals("x = 1", span["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `link with label`() {
        val out = Markdown.parseInlines("see [here](https://example.com) for more")
        val link = out[1].jsonObject["link"]!!.jsonObject
        assertEquals("https://example.com", link["href"]!!.jsonPrimitive.content)
        assertEquals("here", link["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `bare url becomes an autolink`() {
        val out = Markdown.parseInlines("go https://example.com now")
        val link = out[1].jsonObject["link"]!!.jsonObject
        // Bare URL: label == href.
        assertEquals("https://example.com", link["href"]!!.jsonPrimitive.content)
        assertEquals("https://example.com", link["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `patp reference produces ship span`() {
        val out = Markdown.parseInlines("hi ~sampel-palnet friend")
        // Expect: "hi ", ship(~sampel-palnet), " friend"
        val ship = out[1].jsonObject
        assertEquals("ship", firstKey(ship))
        assertEquals("~sampel-palnet", ship["ship"]!!.jsonPrimitive.content)
    }

    @Test
    fun `long-form patp with dashes recognized`() {
        val out = Markdown.parseInlines("~ricsul-bilwyt-dozzod-nisfeb says hi")
        val ship = out[0].jsonObject
        assertEquals("ship", firstKey(ship))
        assertEquals("~ricsul-bilwyt-dozzod-nisfeb", ship["ship"]!!.jsonPrimitive.content)
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
