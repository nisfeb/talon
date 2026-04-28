package io.nisfeb.talon.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Swing-based image picker for desktop. Modal dialog blocks the
 * Swing event thread while open; pickImage() switches to
 * Dispatchers.IO so the Compose coroutine doesn't park the UI
 * thread.
 */
class DesktopFilePicker : FilePicker {
    override suspend fun pickImage(): PickedImage? = withContext(Dispatchers.IO) {
        val chooser = JFileChooser().apply {
            dialogTitle = "Pick an image"
            fileFilter = FileNameExtensionFilter(
                "Images (jpg, png, gif, webp, bmp)",
                "jpg", "jpeg", "png", "gif", "webp", "bmp",
            )
            isAcceptAllFileFilterUsed = false
        }
        val result = chooser.showOpenDialog(null)
        if (result != JFileChooser.APPROVE_OPTION) return@withContext null
        val file = chooser.selectedFile ?: return@withContext null
        runCatching {
            PickedImage(
                bytes = file.readBytes(),
                mimeType = mimeFromExtension(file.extension.lowercase()),
                displayName = file.name,
            )
        }.getOrNull()
    }

    private fun mimeFromExtension(ext: String): String = when (ext) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        else -> "application/octet-stream"
    }
}
