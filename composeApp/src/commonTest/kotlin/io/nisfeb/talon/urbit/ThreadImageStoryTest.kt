package io.nisfeb.talon.urbit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A thread reply used to send an attached image as `[alt](url)` markdown
 * text, which renders as a clickable link, not a picture. Both posts and
 * replies now carry the same structured image block; this pins that the
 * block parses to a rendered [StoryPart.Image] rather than a link.
 */
class ThreadImageStoryTest {

    @Test
    fun `an image story parses to a rendered image, not a link`() {
        val story = imageStory("https://x/y.png", width = 640, height = 480, alt = "a cat")
        val parts = Story.parse(story)

        assertEquals(1, parts.size, "one image verse: $parts")
        val image = parts.single()
        assertTrue(image is StoryPart.Image, "must be an image, got ${image::class.simpleName}")
        assertEquals("https://x/y.png", image.src)
        assertEquals(640, image.width)
        assertEquals(480, image.height)
        assertEquals("a cat", image.alt)

        // The bug's fingerprint: nothing should carry the old markdown
        // link, and no part should be plain text.
        assertTrue(parts.none { it is StoryPart.Text }, "no text/link fallback: $parts")
        assertTrue(!story.toString().contains("](" ), "no markdown-link syntax on the wire")
    }
}
