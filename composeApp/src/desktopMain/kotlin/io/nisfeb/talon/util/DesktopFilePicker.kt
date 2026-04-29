package io.nisfeb.talon.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Swing-based image picker for desktop. Swing components must only
 * be touched on the AWT event thread (EDT) — calling
 * showOpenDialog() from an arbitrary IO thread is undefined
 * behavior and on macOS specifically tends to deadlock or crash
 * AppKit. The dialog blocks the EDT while open, but Compose's
 * coroutine isn't on the Swing thread so the UI stays responsive.
 *
 * File reads run on Dispatchers.IO afterward so they don't park
 * the EDT for large images.
 */
class DesktopFilePicker : FilePicker {
    override suspend fun pickImage(): PickedImage? {
        val file = withContext(Dispatchers.Swing) {
            val chooser = JFileChooser().apply {
                dialogTitle = "Pick an image"
                fileFilter = FileNameExtensionFilter(
                    "Images (jpg, png, gif, webp, bmp)",
                    "jpg", "jpeg", "png", "gif", "webp", "bmp",
                )
                isAcceptAllFileFilterUsed = false
            }
            if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) null
            else chooser.selectedFile
        } ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                PickedImage(
                    bytes = file.readBytes(),
                    mimeType = mimeFromExtension(file.extension.lowercase()),
                    displayName = file.name,
                )
            }.getOrNull()
        }
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
