package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.nisfeb.talon.util.toByteArray
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.UIPasteboard

// File-drop is a desktop affordance; iOS has no Compose equivalent.
actual fun Modifier.fileDropTarget(
    enabled: Boolean,
    onFiles: (List<DroppedFile>) -> Unit,
): Modifier = this

/**
 * Read an image off the iOS pasteboard.
 *
 * Only ever called from an explicit user action (the composer's paste
 * button), never speculatively: touching `image` counts as a read and
 * iOS shows the "Talon pasted from …" banner for it. [clipboardHasImage]
 * is the cheap, banner-free check used to decide whether to *offer*
 * the action.
 */
actual fun readClipboardImageOrNull(): DroppedFile? {
    val board = UIPasteboard.generalPasteboard
    if (!board.hasImages) return null
    val image = board.image ?: return null
    // PNG keeps screenshots and pasted graphics lossless; fall back to
    // JPEG for anything PNG can't encode (rare, but it returns null
    // rather than throwing, and an unsendable paste is worse than a
    // recompressed one).
    UIImagePNGRepresentation(image)?.let {
        return DroppedFile("pasted.png", "image/png", it.toByteArray())
    }
    UIImageJPEGRepresentation(image, 0.9)?.let {
        return DroppedFile("pasted.jpg", "image/jpeg", it.toByteArray())
    }
    return null
}

/**
 * True when the pasteboard holds an image.
 *
 * `hasImages` inspects the pasteboard's declared types without reading
 * its contents, so unlike [readClipboardImageOrNull] it does not trip
 * the iOS paste notification — safe to call while composing.
 */
actual fun clipboardHasImage(): Boolean = UIPasteboard.generalPasteboard.hasImages

// iOS has no rich-content receiver for text fields: the system Paste
// item on a plain field yields text only, and Compose exposes no hook
// to claim an image from it. The composer offers an explicit paste
// button instead (see needsManualImagePaste), so this stays a no-op.
@Composable
actual fun Modifier.imagePasteTarget(
    enabled: Boolean,
    onImage: (DroppedFile) -> Unit,
): Modifier = this
