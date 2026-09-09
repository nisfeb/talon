package io.nisfeb.talon.ui

import androidx.compose.ui.text.buildAnnotatedString
import io.nisfeb.talon.urbit.StoryPart
import io.nisfeb.talon.urbit.URL_TAG
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GalleryPreviewUrlTest {
    private fun link(title: String?, image: String?) =
        StoryPart.LinkPreview("https://a.b/c", title, null, image, null)

    private fun inlineUrl(url: String) = StoryPart.Text(
        buildAnnotatedString {
            pushStringAnnotation(URL_TAG, url)
            append(url)
            pop()
        },
    )

    @Test
    fun `an empty link block is fetched, a filled one is not`() {
        assertEquals("https://a.b/c", galleryPreviewUrl(listOf(link(null, null))))
        assertNull(galleryPreviewUrl(listOf(link("Title", null))))
        assertNull(galleryPreviewUrl(listOf(link(null, "https://a.b/i.png"))))
    }

    @Test
    fun `a legacy inline link is fetched`() {
        assertEquals("https://x.y/z", galleryPreviewUrl(listOf(inlineUrl("https://x.y/z"))))
        assertNull(galleryPreviewUrl(listOf(StoryPart.Text(buildAnnotatedString { append("no link") }))))
    }
}
