package io.nisfeb.talon.urbit

import io.nisfeb.talon.ui.firstFurumLink
import io.nisfeb.talon.ui.firstLinkUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Furum's links as furum itself makes them (code/nex/furum/app.hoon,
 * the /b/~host/board[/id] routes), and Talon's f/ shorthand for them.
 */
class FurumLinkTest {
    private val cats = FurumRef("~zod", "cats")
    private val post = FurumRef("~sampel-palnet", "dev-log", 42)

    @Test
    fun `a furum page on any ship is its board or post`() {
        assertEquals(cats, FurumLink.parse("https://zod.example/apps/furum/b/~zod/cats"))
        assertEquals(post, FurumLink.parse("https://someone.else.io/apps/furum/b/~sampel-palnet/dev-log/42"))
        assertEquals(post, FurumLink.parse("http://localhost:8080/apps/furum/b/~sampel-palnet/dev-log/42/"))
        assertEquals(cats, FurumLink.parse("https://z.example/apps/furum/b/~zod/cats?sort=new&page=2"))
        assertEquals(cats, FurumLink.parse("https://z.example/apps/furum/b/~zod/cats#top"))
    }

    @Test
    fun `furum's other pages and other apps are not boards`() {
        listOf(
            "https://z.example/apps/furum/b/~zod/cats/submit",
            "https://z.example/apps/furum/b/~zod/cats/mod",
            "https://z.example/apps/furum/b/~zod/cats/42/edit",
            "https://z.example/apps/furum/b/zod/cats",
            "https://z.example/apps/furum",
            "https://z.example/apps/furum/about",
            "https://z.example/apps/lattice/b/~zod/cats",
            "https://z.example/apps/furum/b/~zod/Cats",
        ).forEach { assertNull(FurumLink.parse(it), it) }
    }

    @Test
    fun `the shorthand is a board or a post, and only whole`() {
        assertEquals(cats, FurumLink.parse("f/~zod/cats"))
        assertEquals(post, FurumLink.parse("f/~sampel-palnet/dev-log/42"))
        assertNull(FurumLink.parse("~zod/cats"), "a Tlon group is written this way")
        assertNull(FurumLink.parse("r/cats"))
        assertNull(FurumLink.parse("f/cats"), "a board is only unique with its host")
    }

    @Test
    fun `in text, the shorthand is found where it stands and nowhere it does not`() {
        fun found(text: String) = FurumLink.findRanges(text).map { text.substring(it) }
        assertEquals(
            listOf("f/~zod/cats", "f/~sampel-palnet/dev-log/42"),
            found("see f/~zod/cats and f/~sampel-palnet/dev-log/42."),
        )
        assertEquals(listOf("f/~zod/cats"), found("(f/~zod/cats)"))
        assertEquals(listOf("f/~zod/cats"), found("f/~zod/cats-"), "no trailing hyphen on a board")
        assertEquals(emptyList(), found("https://x.io/f/~zod/cats"), "inside a URL")
        assertEquals(emptyList(), found("urb://~zod/f/~bus/cats"), "inside an urb:// address")
        assertEquals(emptyList(), found("elf/~zod/cats"), "inside a word")
        assertEquals(emptyList(), found("f/~zod/cats/submit"), "running on into a path")
        assertEquals(emptyList(), found("join ~zod/cats"))
    }

    @Test
    fun `it opens on the reader's ship, and asks it for the card there`() {
        assertEquals("f/~sampel-palnet/dev-log/42", post.shorthand)
        assertEquals("https://me.example/apps/furum/b/~sampel-palnet/dev-log/42", post.pageUrl("https://me.example/"))
        assertEquals("https://me.example/apps/furum/b/~zod/cats", cats.pageUrl("https://me.example"))
        assertEquals("https://me.example/apps/furum/preview?board=~sampel-palnet/dev-log&post=42", post.previewUrl("https://me.example"))
        assertEquals("https://me.example/apps/furum/preview?board=~zod/cats", cats.previewUrl("https://me.example"))
    }

    @Test
    fun `a message's shorthand is a link, and gets furum's card and not the web's`() {
        val parts = Story.parse(chatTextToStory("have you seen f/~zod/cats yet"))
        val text = (parts.single() as StoryPart.Text).text
        assertEquals(listOf("f/~zod/cats"), text.getStringAnnotations(URL_TAG, 0, text.length).map { it.item })
        assertEquals("f/~zod/cats", firstFurumLink(parts))
        assertNull(firstLinkUrl(parts), "the web card would fetch a URL that is not one")
    }

    @Test
    fun `a furum page linked in a message gets furum's card too`() {
        val parts = Story.parse(
            kotlinx.serialization.json.Json.parseToJsonElement(
                """[{"inline":["look ",{"link":{"href":"https://sharer.io/apps/furum/b/~zod/cats/7","content":"this"}}," and ",{"link":{"href":"https://example.com/a","content":"that"}}]}]""",
            ),
        )
        assertEquals("https://sharer.io/apps/furum/b/~zod/cats/7", firstFurumLink(parts))
        assertEquals("https://example.com/a", firstLinkUrl(parts))
    }

    // Its ~host would otherwise be a mention, which notifies the host.
    @Test
    fun `the composer sends the shorthand as text, not a mention of the host`() {
        val story = chatTextToStory("have you seen f/~zod/cats/42 yet ~bus")
        val wire = story.toString()
        assertEquals(1, Regex("\"ship\"").findAll(wire).count(), "only ~bus is a mention: $wire")
        assertEquals(true, "f/~zod/cats/42" in wire, wire)
        // Only where it starts: inside a word it is not one, and ~zod is.
        assertEquals(null, FurumLink.shorthandAt("elf/~zod/cats", 2))
        assertEquals(15, FurumLink.shorthandAt("see f/~zod/cats", 4))
    }
}
