package io.nisfeb.talon.urbit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
    fun `single-newline-separated lines collapse into one verse with internal breaks`() {
        // Soft line breaks (single \n) used to split into N verses,
        // which rendered with a blank row on top of the column gap
        // (visible double-spacing). Now they live inside one verse so
        // each \n renders at line-height spacing.
        val story = chatTextToStory("one\ntwo\nthree")
        assertEquals(1, story.size)
        val inline = inlineOf(story[0].jsonObject)
        val texts = inline.filterIsInstance<JsonPrimitive>().map { it.content }
        assertEquals(listOf("one", "two", "three"), texts)
        val breakCount = inline.count { (it as? JsonObject)?.containsKey("break") == true }
        assertEquals(2, breakCount)
        // No trailing break — the renderer would otherwise draw a
        // blank row beneath the last line.
        assertFalse(
            (inline.last() as? JsonObject)?.containsKey("break") == true,
        )
    }

    @Test
    fun `blank line creates a paragraph break — two verses`() {
        // Double-newline = explicit paragraph gap. Each side becomes
        // its own verse so StoryRenderer's column arrangement renders
        // the gap on top of normal line spacing.
        val story = chatTextToStory("one\n\ntwo")
        assertEquals(2, story.size)
        assertEquals("one", (inlineOf(story[0].jsonObject)[0] as JsonPrimitive).content)
        assertEquals("two", (inlineOf(story[1].jsonObject)[0] as JsonPrimitive).content)
    }

    // ─── blockquote grouping ────────────────────────────────────

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

    // ─── fenced code blocks ─────────────────────────────────────

    /** Extract `block.code.{code,lang}` from a verse, or null. */
    private fun codeBlock(verse: JsonObject): Pair<String, String>? {
        val block = verse["block"] as? JsonObject ?: return null
        val code = block["code"] as? JsonObject ?: return null
        return (code["code"] as JsonPrimitive).content to
            (code["lang"] as JsonPrimitive).content
    }

    @Test
    fun `triple backtick fenced block becomes a code verse`() {
        val story = chatTextToStory("```\nfun main() {}\n```")
        assertEquals(1, story.size)
        val (body, lang) = assertNotNull(codeBlock(story[0].jsonObject))
        assertEquals("fun main() {}", body)
        // Empty lang normalizes to "text" — the agent dejs runs
        // (se %tas) which rejects "" (not a valid @tas term).
        assertEquals("text", lang)
    }

    @Test
    fun `language tag is lowercased and stripped to valid @tas chars`() {
        val story = chatTextToStory("```C++ Plus\nint x = 0;\n```")
        val (_, lang) = assertNotNull(codeBlock(story[0].jsonObject))
        // "C++ Plus" → lowercase → strip non-[a-z0-9-] → "cplus".
        // The point is the value is always a valid @tas, matching the
        // shape Tlon's webclient produces.
        assertEquals("cplus", lang)
    }

    @Test
    fun `code block surrounded by prose yields three verses`() {
        val story = chatTextToStory("look:\n```\nx = 1\n```\nokay?")
        assertEquals(3, story.size)
        assertNull(codeBlock(story[0].jsonObject))
        assertNotNull(codeBlock(story[1].jsonObject))
        assertNull(codeBlock(story[2].jsonObject))
    }

    @Test
    fun `multi-line code body preserves internal newlines`() {
        val story = chatTextToStory("```\na\nb\nc\n```")
        val (body, _) = assertNotNull(codeBlock(story[0].jsonObject))
        assertEquals("a\nb\nc", body)
    }

    @Test
    fun `unclosed fence falls back to inline so the rest still renders`() {
        // No closing fence — the opener becomes literal text and the
        // body lines follow as plain inlines. Verifies we don't eat
        // the rest of the message when the user forgot the closer.
        val story = chatTextToStory("```\nfun main() {}\nstill writing")
        assertEquals(1, story.size)
        assertNull(codeBlock(story[0].jsonObject))
        assertTrue("still writing" in story.toString(), "the body must not be dropped: $story")
    }

    // ─── a pasted group reference ───────────────────────────────

    private fun groupCite(flag: String) = kotlinx.serialization.json.Json.parseToJsonElement("""{"block":{"cite":{"group":"$flag"}}}""")

    @Test
    fun `a pasted group reference goes out as the group's cite, as Tlon sends it`() {
        assertEquals(JsonArray(listOf(groupCite("~bus/club"))), chatTextToStory("/1/group/~bus/club"))
        // In a chat Tlon puts the cite ahead of the words (toPostData).
        val story = chatTextToStory("come join /1/group/~bus/club tonight")
        assertEquals(groupCite("~bus/club"), story[0])
        assertEquals(chatTextToStory("come join  tonight")[0], story[1])
        assertEquals(2, story.size)
    }

    // ─── a pasted message reference ─────────────────────────────

    private fun chanCite(nest: String, where: String) =
        kotlinx.serialization.json.Json.parseToJsonElement("""{"block":{"cite":{"chan":{"nest":"$nest","where":"$where"}}}}""")

    @Test
    fun `a pasted message reference goes out as the post's cite, as Tlon's pathToCite makes it`() {
        val ref = "/1/chan/chat/~darduc-mitfen/v3l8bgc0/msg/170.141.184.508.207.337.420.778.996.591.277.113.344"
        assertEquals(
            JsonArray(listOf(chanCite("chat/~darduc-mitfen/v3l8bgc0", "/msg/170.141.184.508.207.337.420.778.996.591.277.113.344"))),
            chatTextToStory(ref),
        )
        // Ahead of the words, as Tlon puts a reference in a chat.
        val story = chatTextToStory("look at this $ref please")
        assertEquals(chanCite("chat/~darduc-mitfen/v3l8bgc0", "/msg/170.141.184.508.207.337.420.778.996.591.277.113.344"), story[0])
        assertEquals(chatTextToStory("look at this  please")[0], story[1])
        // A reply, a plain-digit id as some clients write it, a heap post.
        assertEquals(JsonArray(listOf(chanCite("chat/~bus/talk", "/msg/170.141/170.142"))), chatTextToStory("/1/chan/chat/~bus/talk/msg/170.141/170.142"))
        assertEquals(JsonArray(listOf(chanCite("heap/~bus/links", "/msg/170141184508"))), chatTextToStory("/1/chan/heap/~bus/links/msg/170141184508"))
    }

    @Test
    fun `what Talon sends opens the post it names`() {
        val sent = chatTextToStory(io.nisfeb.talon.urbit.TalonLink.messageReference("chat/~bus/talk", "170141184508207337420778996591277113344", "170141184508000000000000000000000000000"))
        val cite = sent[0].jsonObject["block"]!!.jsonObject["cite"]!!.jsonObject
        assertEquals(
            CiteTarget.ChannelPost("chat/~bus/talk", "170.141.184.508.000.000.000.000.000.000.000.000.000", "170.141.184.508.207.337.420.778.996.591.277.113.344"),
            parseCite(cite).target,
        )
    }

    @Test
    fun `a reference's host is no mention, and a ship after it still is`() {
        val text = "/1/chan/chat/~bus/talk/msg/170.141 ask ~zod"
        assertEquals(listOf(text.indexOf("~zod") until text.length), mentionRanges(text))
    }

    @Test
    fun `a group and a post in one message are two cites, in the order they stand`() {
        val story = chatTextToStory("/1/chan/chat/~bus/talk/msg/170.141 and /1/group/~zod/crew")
        assertEquals(chanCite("chat/~bus/talk", "/msg/170.141"), story[0])
        assertEquals(groupCite("~zod/crew"), story[1])
        assertEquals(chatTextToStory("and")[0], story[2])
    }

    @Test
    fun `a message reference shown in code, a quote, a link or a word stays text`() {
        for (text in listOf(
            "`/1/chan/chat/~bus/talk/msg/170.141`", "> /1/chan/chat/~bus/talk/msg/170.141", "https://x.test/1/chan/chat/~bus/talk/msg/170.141",
            "see:/1/chan/chat/~bus/talk/msg/170.141", "/1/chan/chat/~bus/talk", "/1/chan/chat/~bus/talk/msg/", "/1/chan/notes/~bus/n/note/1",
        )) {
            assertTrue(chatTextToStory(text).none { "\"chan\"" in it.toString() }, text)
        }
    }

    @Test
    fun `the same reference twice is one cite, and two groups are two`() {
        val story = chatTextToStory("/1/group/~bus/club\n/1/group/~bus/club /1/group/~zod/other")
        assertEquals(JsonArray(listOf(groupCite("~bus/club"), groupCite("~zod/other"))), story)
    }

    @Test
    fun `a reference inside a link or a word stays text`() {
        for (text in listOf("https://x.test/1/group/~bus/club", "see:/1/group/~bus/club", "/1/group/~bus")) {
            assertTrue(chatTextToStory(text).none { "cite" in it.toString() }, text)
        }
    }

    @Test
    fun `a group reference in a code block or a quote is shown, not pulled out as a cite`() {
        for (text in listOf("```\n/1/group/~bus/club\n```", "> /1/group/~bus/club", "see `/1/group/~bus/club`")) {
            val story = chatTextToStory(text)
            assertTrue(story.none { "cite" in it.toString() }, "$text -> $story")
            assertTrue("/1/group/" in story.toString() && "/club" in story.toString(), "the reference stays in the post: $story")
        }
        // Outside the quote it still goes out as one.
        val mixed = chatTextToStory("> as they said\n/1/group/~bus/club")
        assertEquals(groupCite("~bus/club"), mixed[0])
    }

    @Test
    fun `the composer does not take a reference's host for a mention`() {
        val text = "/1/group/~bus/club hey ~bus"
        assertEquals(listOf(text.lastIndexOf("~bus").let { it until it + 4 }), mentionRanges(text))
    }
}
