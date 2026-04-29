package io.nisfeb.talon.urbit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class ChatStoryTest {

    private fun inlineOf(verse: JsonObject): JsonArray =
        verse["inline"]!!.jsonArray

    /** True when any inline element in [verse] is a blockquote wrapper. */
    private fun hasBlockquote(verse: JsonObject): Boolean =
        inlineOf(verse).any {
            (it as? JsonObject)?.containsKey("blockquote") == true
        }

    // ─── plain passthrough ──────────────────────────────────────

    @Test
    fun `empty input yields a single empty-inline verse`() {
        // split('\n') on "" returns [""], so one verse survives; that
        // matches the existing chat contract (don't drop empty sends
        // silently — let the UI gate them).
        val story = chatTextToStory("")
        assertEquals(1, story.size)
    }

    @Test
    fun `single plain line becomes one inline verse, no trailing break`() {
        val story = chatTextToStory("hello")
        assertEquals(1, story.size)
        val inline = inlineOf(story[0].jsonObject)
        // Last verse has NO break appended.
        assertFalse(inline.any { (it as? JsonObject)?.containsKey("break") == true })
        assertEquals("hello", (inline[0] as JsonPrimitive).content)
    }

    @Test
    fun `multi-line plain text gets a break on every verse except the last`() {
        val story = chatTextToStory("one\ntwo\nthree")
        assertEquals(3, story.size)
        val hasBreak = { v: JsonObject ->
            inlineOf(v).any { (it as? JsonObject)?.containsKey("break") == true }
        }
        assertTrue(hasBreak(story[0].jsonObject))
        assertTrue(hasBreak(story[1].jsonObject))
        assertFalse(hasBreak(story[2].jsonObject))
    }

    // ─── blockquote grouping ────────────────────────────────────

    @Test
    fun `single quoted line emits one blockquote verse`() {
        val story = chatTextToStory("> quoted")
        assertEquals(1, story.size)
        val inline = inlineOf(story[0].jsonObject)
        // A blockquote verse has exactly one inline element — the
        // blockquote wrapper.
        assertEquals(1, inline.size)
        val bq = inline[0].jsonObject["blockquote"]!!.jsonArray
        assertEquals("quoted", (bq[0] as JsonPrimitive).content)
    }

    @Test
    fun `consecutive quote lines merge into one blockquote`() {
        val story = chatTextToStory("> first\n> second\n> third")
        assertEquals(1, story.size)
        val bq = inlineOf(story[0].jsonObject)[0].jsonObject["blockquote"]!!.jsonArray
        // Three lines → three text primitives + two breaks in between.
        val texts = bq.filterIsInstance<JsonPrimitive>().map { it.content }
        assertEquals(listOf("first", "second", "third"), texts)
        val breakCount = bq.count { (it as? JsonObject)?.containsKey("break") == true }
        assertEquals(2, breakCount)
    }

    @Test
    fun `quote then plain produces two verses in order`() {
        val story = chatTextToStory("> quoted\nfollow-up")
        assertEquals(2, story.size)
        // Verse 0 is the blockquote.
        val bq = inlineOf(story[0].jsonObject)[0].jsonObject
        assertTrue(bq.containsKey("blockquote"))
        // Verse 1 is plain text (last verse → no trailing break).
        val plain = inlineOf(story[1].jsonObject)
        assertEquals("follow-up", (plain[0] as JsonPrimitive).content)
    }

    @Test
    fun `plain then quote then plain produces three verses`() {
        val story = chatTextToStory("intro\n> said\nreply")
        assertEquals(3, story.size)
        assertFalse(hasBlockquote(story[0].jsonObject))
        assertTrue(hasBlockquote(story[1].jsonObject))
        assertFalse(hasBlockquote(story[2].jsonObject))
    }

    @Test
    fun `two separate quoted groups split by a plain line do not merge`() {
        val story = chatTextToStory("> a\nseparator\n> b")
        assertEquals(3, story.size)
        assertTrue(hasBlockquote(story[0].jsonObject))
        assertFalse(hasBlockquote(story[1].jsonObject))
        assertTrue(hasBlockquote(story[2].jsonObject))
    }

    @Test
    fun `quote line gets inline formatting applied inside the blockquote`() {
        val story = chatTextToStory("> say **hi** to ~sampel-palnet")
        val bq = inlineOf(story[0].jsonObject)[0].jsonObject["blockquote"]!!.jsonArray
        // Expect a sequence: "say ", bold("hi"), " to ", ship(...).
        assertTrue(bq.any { (it as? JsonObject)?.containsKey("bold") == true })
        assertTrue(bq.any { (it as? JsonObject)?.containsKey("ship") == true })
    }

    // ─── guardrails against over-eager matching ─────────────────

    @Test
    fun `gt without trailing space is plain text`() {
        // Markdown convention: `>text` (no space) is not a quote.
        val story = chatTextToStory(">notaquote")
        assertEquals(1, story.size)
        assertFalse(hasBlockquote(story[0].jsonObject))
        val inline = inlineOf(story[0].jsonObject)
        assertEquals(">notaquote", (inline[0] as JsonPrimitive).content)
    }

    @Test
    fun `gt mid-line is plain text`() {
        val story = chatTextToStory("a > b")
        assertEquals(1, story.size)
        assertFalse(hasBlockquote(story[0].jsonObject))
    }
}
