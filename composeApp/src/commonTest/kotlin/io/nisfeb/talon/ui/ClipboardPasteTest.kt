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

    // Some apps copy a picture with words beside it; Ctrl+V took the
    // picture and the words were lost.
    @Test
    fun `ctrl v pastes the text where there is any and never reads the image then`() {
        var read = 0
        assertEquals(ClipboardPaste.None, ctrlVPaste(hasText = { true }, read = { read++; png }))
        assertEquals(0, read, "an image is not decoded to be put aside")
    }

    @Test
    fun `ctrl v with no text takes the image or says it is too large`() {
        assertEquals(ClipboardPaste.Image(png), ctrlVPaste(hasText = { false }, read = { png }))
        assertEquals(ClipboardPaste.None, ctrlVPaste(hasText = { false }, read = { null }))
        assertEquals(
            ClipboardPaste.Problem("That image is too large to paste. Save it as a file and attach it instead."),
            ctrlVPaste(hasText = { false }, read = { throw ImageTooLargeToPaste() }),
        )
    }
}
