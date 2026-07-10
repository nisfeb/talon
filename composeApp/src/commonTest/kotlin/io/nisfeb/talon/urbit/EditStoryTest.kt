package io.nisfeb.talon.urbit

import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Editing a quoted post used to flatten its cite into literal text —
 * "~zod: hi" got re-posted as prose and the reference was destroyed.
 * The cite (and images / link previews) must survive an edit; only the
 * text is editable.
 */
class EditStoryTest {

    private val cite = """{"block":{"cite":{"chan":{"nest":"chat/~zod/general","where":"/msg/123"}}}}"""
    private val quotePost = """[$cite,{"inline":["hello there"]}]"""

    private fun blocks(content: kotlinx.serialization.json.JsonArray) =
        content.mapNotNull { (it as? JsonObject)?.get("block") }

    @Test
    fun `editing a quote keeps the cite and swaps only the text`() {
        assertEquals("hello there", editableText(quotePost))

        val edited = editedStory(quotePost, "hello again")
        assertEquals(1, blocks(edited).size, "the cite must survive: $edited")
        assertTrue(edited.toString().contains("/msg/123"), "cite target preserved")
        assertTrue(edited.toString().contains("hello again"))
        assertTrue(!edited.toString().contains("hello there"), "old text is gone")
    }

    @Test
    fun `the cite stays above the text it was quoted for`() {
        val edited = editedStory(quotePost, "new body")
        val first = edited.first() as JsonObject
        assertTrue(first.containsKey("block"), "quote leads the message")
    }

    @Test
    fun `a block below the text keeps its position`() {
        val content = """[{"inline":["look"]},$cite]"""
        val edited = editedStory(content, "look again")
        assertTrue((edited.first() as JsonObject).containsKey("inline"))
        assertTrue((edited.last() as JsonObject).containsKey("block"))
    }

    @Test
    fun `images and link previews are preserved, code round-trips as text`() {
        val image = """{"block":{"image":{"src":"http://x/y.png","width":1,"height":2,"alt":"pic"}}}"""
        val edited = editedStory("""[$image,{"inline":["cap"]}]""", "new cap")
        assertTrue(edited.toString().contains("http://x/y.png"), "image survives")

        // Code is the one block the text form carries faithfully — it must
        // NOT be preserved as a block too, or the edit duplicates it.
        val code = """[{"block":{"code":{"code":"val x = 1","lang":"kotlin"}}}]"""
        assertEquals("```\nval x = 1\n```", editableText(code))
        val reEdited = editedStory(code, "```\nval x = 2\n```")
        assertEquals(1, blocks(reEdited).size, "exactly one code block, not two")
        assertTrue(reEdited.toString().contains("val x = 2"))
    }

    @Test
    fun `a quote with no text at all gains the edited body after the cite`() {
        val content = "[$cite]"
        assertEquals("", editableText(content))
        val edited = editedStory(content, "now with words")
        assertTrue((edited.first() as JsonObject).containsKey("block"))
        assertTrue(edited.toString().contains("now with words"))
    }

    @Test
    fun `plain posts and unparseable content behave exactly as before`() {
        val plain = """[{"inline":["just text"]}]"""
        assertEquals("just text", editableText(plain))
        assertEquals(chatTextToStory("edited"), editedStory(plain, "edited"))

        // Garbage content must not throw — fall back to re-parsing the text.
        assertEquals(chatTextToStory("x"), editedStory("not json", "x"))
        assertEquals("", editableText("not json"))
    }

    @Test
    fun `editableText drops the cite label the old dialog used to show`() {
        // The bug's fingerprint: textFor renders a Citation part as a
        // label, which landed in the editor and got posted as prose.
        val text = editableText(quotePost)
        assertTrue(!text.contains("~zod"), "no cite label in the editor: '$text'")
        assertNull(text.lineSequence().firstOrNull { it.contains("msg/123") })
    }
}
