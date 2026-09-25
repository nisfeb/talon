package io.nisfeb.talon.urbit

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Block content as it arrives from the ship, made into what the screens
 * draw: previews, quotes, rules, cites, attachments of shapes Tlon never
 * documented, and the plain text a list preview or a search reads.
 */
class StoryBlocksTest {
    private fun parts(json: String): List<StoryPart> = Story.parse(Json.parseToJsonElement(json))
    private fun one(json: String): StoryPart = parts(json).single()
    private fun block(inner: String) = one("""[{"block":$inner}]""")

    @Test
    fun `a link preview takes its title, image and site from either spelling of the keys`() {
        val camel = block("""{"link":{"url":"https://a.test","meta":{"title":"A","previewImageUrl":"https://a.test/i.png","siteName":"Site"}}}""")
        val kebab = block("""{"link":{"url":"https://a.test","meta":{"title":"A","preview-image-url":"https://a.test/i.png","site-name":"Site"}}}""")
        assertEquals(camel, kebab)
        val p = assertIs<StoryPart.LinkPreview>(camel)
        assertEquals(listOf("A", "https://a.test/i.png", "Site"), listOf(p.title, p.imageUrl, p.siteName))
    }

    @Test
    fun `a header is its text, a rule is a line, and a block quote is quoted in either shape`() {
        assertEquals("Big news", assertIs<StoryPart.Text>(block("""{"header":{"tag":"h1","content":["Big news"]}}""")).text.text)
        assertTrue(assertIs<StoryPart.Text>(block("""{"rule":null}""")).text.text.all { it == '─' })
        val array = assertIs<StoryPart.Text>(block("""{"block-quote":["wise words"]}""")).text.text
        val older = assertIs<StoryPart.Text>(block("""{"blockquote":{"content":["wise words"]}}""")).text.text
        assertEquals("“wise words”", array)
        assertEquals(array, older)
    }

    @Test
    fun `an attachment of an unknown shape becomes a file link with its size`() {
        val p = assertIs<StoryPart.LinkPreview>(block("""{"file":{"url":"https://s.test/minutes.pdf?x=1","size":2048,"mime":"application/pdf"}}"""))
        assertEquals("minutes.pdf • 2.0 KB", p.title)
        assertEquals("📎 File" to "application/pdf", p.siteName to p.description)
    }

    @Test
    fun `a block nobody knows says what it is rather than vanishing`() {
        assertEquals("[sticker]", assertIs<StoryPart.Text>(block("""{"sticker":{"pack":"cats"}}""")).text.text)
    }

    @Test
    fun `cites open what they point at, or say they cannot`() {
        val post = assertIs<StoryPart.Citation>(block("""{"cite":{"chan":{"nest":"chat/~bus/general","where":"/msg/170141184506"}}}"""))
        assertEquals("Post in #general" to "chat/~bus/general", post.label to post.openTarget)
        assertEquals("group:~bus/garden", assertIs<StoryPart.Citation>(block("""{"cite":{"group":"~bus/garden"}}""")).openTarget)
        val url = assertIs<StoryPart.LinkPreview>(block("""{"cite":{"url":{"url":"https://s.test/a.png","name":"a.png"}}}"""))
        assertEquals("Reference" to "https://s.test/a.png", url.siteName to url.url)
        val unknown = assertIs<StoryPart.Citation>(block("""{"cite":{"desk":{"flag":"~bus/app"}}}"""))
        assertEquals("App ~bus/app", unknown.label)
        assertNull(unknown.openTarget)
    }

    @Test
    fun `plain text reads every kind of part the way a preview wants it`() {
        val text = Story.plainText(
            Json.parseToJsonElement(
                """[{"block":{"image":{"src":"https://i.test/a.png","alt":"a cat","width":1,"height":1}}},
                   {"block":{"image":{"src":"https://i.test/b.png","alt":"","width":1,"height":1}}},
                   {"block":{"code":{"code":"x = 1","lang":""}}},
                   {"block":{"link":{"url":"https://a.test","meta":{}}}},
                   {"block":{"cite":{"group":"~bus/garden"}}}]""",
            ),
        )
        assertEquals(listOf("a cat", "[image]", "```", "x = 1", "```", "https://a.test", "Group ~bus/garden"), text.lines())
    }

    @Test
    fun `an urb address in plain text is made a link, and a shortcode an emoji`() {
        val t = assertIs<StoryPart.Text>(one("""[{"inline":["read urb://~zod/notes today :tada:"]}]""")).text
        assertEquals(listOf("urb://~zod/notes"), t.getStringAnnotations(URL_TAG, 0, t.length).map { it.item })
        assertTrue("🎉" in t.text && ":tada:" !in t.text, t.text)
    }
}
