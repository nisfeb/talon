package io.nisfeb.talon.util

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The system's own file picker on desktop, through FileKit: the
 * xdg-desktop-portal on Linux (the desktop's dialog, KDE's or GNOME's;
 * AWT's when no portal runs), NSOpenPanel on macOS, the Windows dialog.
 * It used Swing's JFileChooser, which looks like no system's. FileKit
 * puts each dialog on the thread its platform needs, so this does not.
 *
 * Reentry guard: a Mutex serializes concurrent picks. Without it,
 * double-tapping the attach button would queue two dialogs — the user
 * picks once, dismisses, then a second picker pops up unexpectedly.
 *
 * A file that cannot be read throws, as [rememberImagePicker] promises,
 * so the caller says so rather than the pick doing nothing. The read runs
 * on Dispatchers.IO so a large file does not hold up the UI.
 */
class DesktopFilePicker : FilePicker {
    private val mutex = Mutex()

    override suspend fun pickImage(): PickedImage? {
        val file = pick(FileKitType.File(IMAGE_EXTENSIONS), "Pick an image") ?: return null
        return read(file, io.nisfeb.talon.ui.mimeForName(file.name))
    }

    override suspend fun pickAnyFile(): PickedImage? {
        val file = pick(FileKitType.File(), "Pick a file") ?: return null
        val probed = withContext(Dispatchers.IO) {
            runCatching { java.nio.file.Files.probeContentType(file.toPath()) }.getOrNull()
        }
        return read(file, probed ?: io.nisfeb.talon.ui.mimeForName(file.name))
    }

    private suspend fun pick(type: FileKitType, title: String): File? = mutex.withLock {
        FileKit.openFilePicker(
            type = type,
            dialogSettings = FileKitDialogSettings(title = title, parentWindow = appFrame()),
        )?.file
    }

    private suspend fun read(file: File, mimeType: String): PickedImage = withContext(Dispatchers.IO) {
        PickedImage(bytes = file.readBytes(), mimeType = mimeType, displayName = file.name)
    }

    // Parent the dialog to the app window so it opens over Talon and
    // stays in front — with no parent some WMs center it on the primary
    // monitor or stack it behind the app. Same title-based lookup
    // Main.kt's bring-to-front routine uses.
    private fun appFrame(): java.awt.Frame? =
        java.awt.Frame.getFrames().firstOrNull { it.title == "Talon" }

    private companion object {
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
    }
}
