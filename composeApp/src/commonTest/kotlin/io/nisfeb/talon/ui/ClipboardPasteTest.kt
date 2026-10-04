package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Ctrl+V with an image: it is staged, or the composer says it is too
 * large; only with no image does the field paste the clipboard's text.
 * An image too large to read used to fall through to that text, or to a
 * crash.
 */
class ClipboardPasteTest {
    private val png = DroppedFile("pasted.png", "image/png", byteArrayOf(1, 2, 3))

    @Test
    fun `an image is staged, too large is said, nothing leaves the text paste alone`() {
        assertEquals(ClipboardPaste.Image(png), clipboardPaste { png })
        assertEquals(
            ClipboardPaste.Problem("That image is too large to paste. Save it as a file and attach it instead."),
            clipboardPaste { throw ImageTooLargeToPaste() },
        )
        assertEquals(ClipboardPaste.None, clipboardPaste { null })
    }
}
