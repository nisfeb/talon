package io.nisfeb.talon.ui

import io.nisfeb.talon.urbit.StoryPart
import io.nisfeb.talon.urbit.URL_TAG
import androidx.compose.ui.text.AnnotatedString
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the chat row plays.
 *
 * A video arrives from Tlon as an image block with the `.mp4` in its
 * `src`. The walk that finds playable media used to read only text
 * parts, so such a message went to the image loader, which cannot
 * decode a video, and showed nothing at all.
 */
class StoryMediaTest {

    @Test
    fun `an image part holding a video is playable media`() {
        val parts = listOf(StoryPart.Image(src = "https://x.com/clip.mp4", width = 640, height = 480, alt = ""))
        assertEquals(listOf("https://x.com/clip.mp4" to MediaKind.VIDEO), mediaInStory(parts))
    }

    @Test
    fun `a real image is not, and stays with the image loader`() {
        assertEquals(emptyList(), mediaInStory(listOf(StoryPart.Image(src = "https://x.com/a.png", width = 1, height = 1, alt = ""))))
    }

    @Test
    fun `a sound sent as an image block plays too`() {
        val parts = listOf(StoryPart.Image(src = "https://x.com/note.m4a", width = null, height = null, alt = null))
        assertEquals(listOf("https://x.com/note.m4a" to MediaKind.AUDIO), mediaInStory(parts))
    }

    @Test
    fun `the same url in a block and in the text is one player`() {
        val url = "https://x.com/clip.mp4"
        val text = AnnotatedString.Builder().apply {
            append(url)
            addStringAnnotation(URL_TAG, url, 0, url.length)
        }.toAnnotatedString()
        assertEquals(1, mediaInStory(listOf(StoryPart.Image(src = url, width = 2, height = 1, alt = null), StoryPart.Text(text))).size)
    }
}
