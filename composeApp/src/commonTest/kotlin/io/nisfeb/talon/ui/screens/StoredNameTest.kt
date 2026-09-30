package io.nisfeb.talon.ui.screens

import io.nisfeb.talon.ui.imageUrlsIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A file too big to attach is stored on the ship and linked instead,
 * and an image link is shown where it stands. That last part reads the
 * address for an image extension, and a phone's file picker answers
 * with a content:// id that has none, so the link came back as a bare
 * link and the picture was not there.
 */
class StoredNameTest {
    @Test
    fun `a name without an extension gets the one its type says`() {
        assertEquals("1000012345.jpg", storedName("1000012345", "image/jpeg"))
        assertEquals("shot.png", storedName("shot.png", "image/png"))
        assertEquals("photo.jpg", storedName("photo.jpg", "image/jpeg"))
        assertEquals("scan.webp", storedName("scan", "image/webp; charset=binary"))
        // Only what is rendered is named: a PDF stays as it came.
        assertEquals("report", storedName("report", "application/pdf"))
        assertEquals("file.jpg", storedName("", "image/jpeg"), "something to call it, and still shown in place")
        assertEquals("shot.jpg", storedName("DCIM/Camera/shot.jpg", "image/jpeg"), "the directory is not ours to send")
    }

    @Test
    fun `the stored address is one the reader shows in place`() {
        val url = "https://bucket.example.com/talon/~2026.9.20..12.00.00-" + storedName("1000012345", "image/jpeg")
        assertTrue(url in imageUrlsIn("Here it is\n\n$url"), "an image link is found in the body: $url")
        val pdf = "https://bucket.example.com/talon/~2026.9.20..12.00.00-" + storedName("report", "application/pdf")
        assertTrue(imageUrlsIn("See\n\n$pdf").isEmpty(), "and a document is not claimed to be one")
    }
}
