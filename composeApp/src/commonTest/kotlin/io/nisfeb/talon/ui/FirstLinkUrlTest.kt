package io.nisfeb.talon.ui

import androidx.compose.ui.text.buildAnnotatedString
import io.nisfeb.talon.urbit.StoryPart
import io.nisfeb.talon.urbit.URL_TAG
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins [firstLinkUrl]'s dedup: the client-side LinkPreviewCard must not
 * re-preview a URL the story already shows via a server `block.link`
 * ([StoryPart.LinkPreview]) — otherwise a single URL renders two cards
 * (one above from the story, one below from the client). The reported bug.
 */
class FirstLinkUrlTest {

    /** A Text part with [url] linkified (URL_TAG annotation), as the
     *  story renderer produces for a bare/markdown link. */
    private fun textWithUrl(prefix: String, url: String): StoryPart.Text =
        StoryPart.Text(
            buildAnnotatedString {
                append(prefix)
                pushStringAnnotation(URL_TAG, url)
                append(url)
                pop()
            },
        )

    private fun preview(url: String): StoryPart.LinkPreview =
        StoryPart.LinkPreview(url = url, title = null, description = null, imageUrl = null, siteName = null)

    @Test
    fun `a URL with no server preview gets a client card`() {
        val parts = listOf(textWithUrl("see ", "https://example.com/post"))
        assertEquals("https://example.com/post", firstLinkUrl(parts))
    }

    @Test
    fun `a URL the story already previews is skipped`() {
        // The bug: text URL + matching server block.link → only the
        // story's preview should show, so the client card is suppressed.
        val parts = listOf(
            preview("https://example.com/post"),
            textWithUrl("see ", "https://example.com/post"),
        )
        assertNull(firstLinkUrl(parts))
    }

    @Test
    fun `trailing-slash difference still counts as already previewed`() {
        val parts = listOf(
            preview("https://example.com/post/"),
            textWithUrl("see ", "https://example.com/post"),
        )
        assertNull(firstLinkUrl(parts))
    }

    @Test
    fun `a second un-previewed URL still gets a card`() {
        // First URL is server-previewed; the distinct second URL isn't,
        // so it should still surface a client card.
        val parts = listOf(
            preview("https://example.com/a"),
            textWithUrl("first ", "https://example.com/a"),
            textWithUrl("second ", "https://other.com/b"),
        )
        assertEquals("https://other.com/b", firstLinkUrl(parts))
    }

    @Test
    fun `no links yields null`() {
        assertNull(firstLinkUrl(listOf(StoryPart.Text(buildAnnotatedString { append("plain text") }))))
    }
}
