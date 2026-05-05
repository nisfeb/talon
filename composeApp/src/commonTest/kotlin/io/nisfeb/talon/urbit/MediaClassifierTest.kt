package io.nisfeb.talon.urbit

import io.nisfeb.talon.data.MessageEntity
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaClassifierTest {

    private fun textMessage(json: String): MessageEntity = MessageEntity(
        whom = "~zod", id = "~zod/1.000",
        author = "~zod", sentMs = 0L,
        contentJson = json, kind = "/chat",
    )

    /** Build a Tlon-shaped story (JsonArray of verses) containing one
     *  inline link with the given display text + url. Shape verified
     *  against `Story.parse` at `Story.kt:253` — `content` is a plain
     *  string, not an array. */
    private fun linkVerse(displayText: String, url: String): String =
        """[{"inline":[{"link":{"href":"$url","content":"$displayText"}}]}]"""

    /** One verse with a `block.image`. Shape verified against
     *  `Story.parse` at `Story.kt:401` — `src`, `width`, `height`,
     *  `alt` keys; `width`/`height` are `Long`. */
    private fun imageVerse(src: String, alt: String = ""): String =
        """[{"block":{"image":{"src":"$src","alt":"$alt","width":0,"height":0}}}]"""

    @Test
    fun `photo extensions land as Photo`() {
        val rows = MediaClassifier.extractMedia(textMessage(linkVerse("img", "https://x.com/a.jpg")))
        assertEquals("Photo", rows.single().category)
    }

    @Test
    fun `gif extension lands as Gif regardless of context`() {
        val rows = MediaClassifier.extractMedia(textMessage(linkVerse("party", "https://x.com/cat.gif")))
        assertEquals("Gif", rows.single().category)
    }

    @Test
    fun `gif image attachment also lands as Gif`() {
        val rows = MediaClassifier.extractMedia(textMessage(imageVerse("https://x.com/cat.gif")))
        assertEquals("Gif", rows.single().category)
    }

    @Test
    fun `mp4 lands as Video`() {
        val rows = MediaClassifier.extractMedia(textMessage(linkVerse("clip", "https://x.com/c.mp4")))
        assertEquals("Video", rows.single().category)
    }

    @Test
    fun `mp3 with voice prefix lands as Voice`() {
        val rows = MediaClassifier.extractMedia(textMessage(linkVerse("🎙 Voice 12s", "https://x.com/v.mp3")))
        assertEquals("Voice", rows.single().category)
    }

    @Test
    fun `mp3 without voice prefix lands as Audio`() {
        val rows = MediaClassifier.extractMedia(textMessage(linkVerse("song", "https://x.com/s.mp3")))
        assertEquals("Audio", rows.single().category)
    }

    @Test
    fun `pdf lands as File`() {
        val rows = MediaClassifier.extractMedia(textMessage(linkVerse("doc", "https://x.com/r.pdf")))
        assertEquals("File", rows.single().category)
    }

    @Test
    fun `bare url lands as Link`() {
        val rows = MediaClassifier.extractMedia(textMessage(linkVerse("site", "https://x.com/article")))
        assertEquals("Link", rows.single().category)
    }

    @Test
    fun `query string and fragment do not affect extension match`() {
        val rows = MediaClassifier.extractMedia(textMessage(linkVerse("img", "https://x.com/a.jpg?w=200#crop")))
        assertEquals("Photo", rows.single().category)
    }

    @Test
    fun `mixed case extension matches`() {
        val rows = MediaClassifier.extractMedia(textMessage(linkVerse("img", "https://x.com/a.JPG")))
        assertEquals("Photo", rows.single().category)
    }

    @Test
    fun `dot in path but no extension is Link`() {
        // A bare URL like https://example.com/v1.0/api/users — the dot
        // is in the path, not a filename extension. Should fall to Link.
        val rows = MediaClassifier.extractMedia(textMessage(linkVerse("api", "https://x.com/v1.0/api")))
        assertEquals("Link", rows.single().category)
    }

    @Test
    fun `empty content yields no rows`() {
        val rows = MediaClassifier.extractMedia(textMessage("""[{"inline":[]}]"""))
        assertEquals(emptyList(), rows)
    }

    @Test
    fun `deleted message yields no rows`() {
        val m = textMessage(linkVerse("img", "https://x.com/a.jpg")).copy(isDeleted = true)
        assertEquals(emptyList(), MediaClassifier.extractMedia(m))
    }

    @Test
    fun `duplicate urls in one message dedupe to one row`() {
        // Two verses, both linking the same URL with different display
        // text. Should dedupe to one media row.
        val json = """[
            {"inline":[{"link":{"href":"https://x.com/a.jpg","content":"a"}}]},
            {"inline":[{"link":{"href":"https://x.com/a.jpg","content":"b"}}]}
        ]"""
        val rows = MediaClassifier.extractMedia(textMessage(json))
        assertEquals(1, rows.size)
    }
}
