package io.nisfeb.talon.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 *
 * Reentry guard: a Mutex serializes concurrent pickImage() calls.
 * Without it, double-tapping the attach button would queue two
 * modal dialogs — the user picks once, dismisses, then a second
 * picker pops up unexpectedly.
 */
private const val TAG = "DesktopFilePicker"

class DesktopFilePicker : FilePicker {
    private val mutex = Mutex()

    override suspend fun pickImage(): PickedImage? = mutex.withLock {
        val file = withContext(Dispatchers.Swing) {
            val chooser = JFileChooser().apply {
                dialogTitle = "Pick an image"
                fileFilter = FileNameExtensionFilter(
                    "Images (jpg, png, gif, webp, bmp)",
                    "jpg", "jpeg", "png", "gif", "webp", "bmp",
                )
                isAcceptAllFileFilterUsed = false
            }
            if (chooser.showOpenDialog(appFrame()) != JFileChooser.APPROVE_OPTION) null
            else chooser.selectedFile
        } ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                PickedImage(
                    bytes = file.readBytes(),
                    mimeType = mimeFromExtension(file.extension.lowercase()),
                    displayName = file.name,
                )
            }.onFailure {
                Log.w(TAG, "read failed for ${file.absolutePath}: ${it.message}")
            }.getOrNull()
        }
    }

    override suspend fun pickAnyFile(): PickedImage? = mutex.withLock {
        val file = withContext(Dispatchers.Swing) {
            val chooser = JFileChooser().apply {
                dialogTitle = "Pick a file"
                isAcceptAllFileFilterUsed = true
            }
            if (chooser.showOpenDialog(appFrame()) != JFileChooser.APPROVE_OPTION) null
            else chooser.selectedFile
        } ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val probedMime = runCatching {
                    java.nio.file.Files.probeContentType(file.toPath())
                }.getOrNull()
                PickedImage(
                    bytes = file.readBytes(),
                    mimeType = probedMime
                        ?: mimeFromExtension(file.extension.lowercase()),
                    displayName = file.name,
                )
            }.onFailure {
                Log.w(TAG, "read failed for ${file.absolutePath}: ${it.message}")
            }.getOrNull()
        }
    }

    // Parent the chooser to the app window so it opens over Talon and
    // stays in front — showOpenDialog(null) lets some WMs center it on
    // the primary monitor or stack it behind the app. Same title-based
    // lookup Main.kt's bring-to-front routine uses; runs on the EDT.
    private fun appFrame(): java.awt.Frame? =
        java.awt.Frame.getFrames().firstOrNull { it.title == "Talon" }

    private fun mimeFromExtension(ext: String): String = when (ext) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        else -> "application/octet-stream"
    }
}
