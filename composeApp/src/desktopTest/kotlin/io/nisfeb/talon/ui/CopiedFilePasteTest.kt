package io.nisfeb.talon.ui

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * An image copied in a file manager pastes as that image (a user's
 * report, 2026-11-01: Ctrl+C on a picture in Dolphin, Ctrl+V in Talon,
 * and no picture). The clipboard holds it as a file list beside its path
 * as text, and no image.
 */
class CopiedFilePasteTest {
    private val dir = createTempDirectory("talon-paste").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun file(name: String, bytes: ByteArray = name.toByteArray()) = File(dir, name).apply { writeBytes(bytes) }

    /** What Dolphin puts on the clipboard: the files, and their paths as text. */
    private fun copied(vararg files: File) = object : Transferable {
        override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor, DataFlavor.stringFlavor)
        override fun isDataFlavorSupported(flavor: DataFlavor) = flavor in transferDataFlavors
        override fun getTransferData(flavor: DataFlavor): Any = when (flavor) {
            DataFlavor.javaFileListFlavor -> files.toList()
            else -> files.joinToString("\n") { it.path }
        }
    }

    @Test
    fun `a copied picture is read as itself, its own bytes, name and type`() {
        val gif = byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61)
        val pasted = readCopiedImageFile(copied(file("notes.txt"), file("cat.gif", gif)))
        assertEquals(DroppedFile("cat.gif", "image/gif", gif), pasted, "the first image among the copied files, not re-encoded")
    }

    @Test
    fun `copied files with no picture among them, a folder, a missing file, or plain text are not a picture`() {
        assertNull(readCopiedImageFile(copied(file("notes.txt"), file("report.pdf"))))
        assertNull(readCopiedImageFile(copied(File(dir, "album.png").apply { mkdir() })))
        assertNull(readCopiedImageFile(copied(File(dir, "gone.png"))))
        assertNull(readCopiedImageFile(StringSelection("/home/me/photo.png")), "a typed path is text")
    }
}
