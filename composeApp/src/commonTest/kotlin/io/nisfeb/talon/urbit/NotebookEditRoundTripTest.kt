package io.nisfeb.talon.urbit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Editing a notebook post seeds the composer with RawMarkdown and
 * saves through MarkdownBlocks.toStory + mergeEdit. Everything a
 * post can hold must survive that trip unchanged.
 */
class NotebookEditRoundTripTest {
    private fun story(s: String) = Json.parseToJsonElement(s) as JsonArray

    @Test
    fun `literal markup in prose is escaped and comes back as text`() {
        val prose = story("""[{"inline":["a * b * c, ~sampel-palnet, [x](y), 5 _ 6"]}]""")
        val md = RawMarkdown.fromStory(prose)
        assertEquals("""a \* b \* c, \~sampel-palnet, \[x](y), 5 \_ 6""", md)
        assertEquals(prose, MarkdownBlocks.toStory(md))
        assertEquals(prose, chatTextToStory(md))
    }

    @Test
    fun `a paragraph that starts like a header or list stays a paragraph`() {
        val prose = story("""[{"inline":["# not a header"]},{"inline":["- not a list"]}]""")
        val md = RawMarkdown.fromStory(prose)
        assertEquals("\\# not a header\n\n\\- not a list", md)
        assertEquals(prose, MarkdownBlocks.toStory(md))
    }

    @Test
    fun `an image keeps its dimensions through an edit`() {
        val prior = story(
            """[{"block":{"image":{"src":"https://i/a.png","width":640,"height":480,"alt":"pic"}}},{"inline":["text"]}]""",
        )
        val md = RawMarkdown.fromStory(prior)
        assertEquals("![pic](https://i/a.png)\n\ntext", md)
        assertEquals(prior, MarkdownBlocks.mergeEdit(prior, MarkdownBlocks.toStory(md)))
    }

    @Test
    fun `a cite is kept at its position across an edit`() {
        val cite = """{"block":{"cite":{"chan":{"nest":"chat/~zod/g","where":"/msg/1"}}}}"""
        val prior = story("""[$cite,{"inline":["body"]}]""")
        assertEquals("body", RawMarkdown.fromStory(prior))
        assertEquals(
            story("""[$cite,{"inline":["body 2"]}]"""),
            MarkdownBlocks.mergeEdit(prior, MarkdownBlocks.toStory("body 2")),
        )
    }

    @Test
    fun `nested list items are kept, flattened`() {
        val nested = story(
            """[{"block":{"listing":{"list":{"type":"unordered","items":[{"item":["a"]},{"list":{"type":"unordered","items":[{"item":["b"]}]}},{"item":["c"]}]}}}}]""",
        )
        val md = RawMarkdown.fromStory(nested)
        assertEquals("- a\n  - b\n- c", md)
        assertEquals(
            story("""[{"block":{"listing":{"list":{"type":"unordered","items":[{"item":["a"]},{"item":["b"]},{"item":["c"]}]}}}}]"""),
            MarkdownBlocks.toStory(md),
        )
    }

    @Test
    fun `deep headers and block-level quotes round-trip`() {
        val h4 = MarkdownBlocks.toStory("#### deep")
        assertEquals("#### deep", RawMarkdown.fromStory(h4))
        assertEquals(h4, MarkdownBlocks.toStory("#### deep"))
        assertEquals("> q", RawMarkdown.fromStoryJson("""[{"block":{"block-quote":["q",{"break":null}]}}]"""))
    }
}
