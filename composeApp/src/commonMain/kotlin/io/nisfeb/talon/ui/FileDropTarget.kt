package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Cross-platform "drop a file onto me to upload it" modifier. Wraps
 * the chat composer so the user can drag from their OS file manager
 * and drop onto the chat to send. Common contract; per-platform
 * actual:
 *
 *  - desktop: hooks `Modifier.onExternalDrag`, reads the dropped file
 *    bytes, hands one [DroppedFile] per file to [onFiles]. Image
 *    types route to `strategy.sendImage`; everything else falls back
 *    to upload + bare-URL send (same path the paperclip / file
 *    button uses).
 *  - android: no-op for now; touch-mode file drop isn't a thing
 *    Compose currently exposes. A future rc adds paste-from-clipboard
 *    instead.
 *
 * The [enabled] flag toggles the target on/off without re-creating
 * the modifier — caller passes `canSend && !uploading` so a drop
 * during an in-flight upload is rejected.
 */
expect fun Modifier.fileDropTarget(
    enabled: Boolean,
    onFiles: (List<DroppedFile>) -> Unit,
): Modifier

/** A file the user dropped or pasted. Bytes are eagerly read so the
 *  caller doesn't have to manage IO scope. */
data class DroppedFile(
    val name: String,
    val mimeType: String,
    val bytes: ByteArray,
) {
    /** True for any image-shaped MIME, used by callers to pick the
     *  structured `sendImage` path over the plain `sendText` URL
     *  fallback. */
    val isImage: Boolean
        get() = mimeType.startsWith("image/")

    // bytes is a ByteArray — the data class default equals/hashCode
    // would compare by reference. Override with content equality so
    // tests + dedupe work. Not load-bearing for production; cheap.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DroppedFile) return false
        if (name != other.name) return false
        if (mimeType != other.mimeType) return false
        return bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

/** Read the OS clipboard. Returns a single [DroppedFile] if the
 *  clipboard currently holds an image; null otherwise (text-only,
 *  empty, or unreadable). Used by the chat composer's Ctrl+V
 *  intercept on desktop. Android returns null until we wire
 *  ContentReceiver. */
expect fun readClipboardImageOrNull(): DroppedFile?

/**
 * True when the OS clipboard holds an image, checked cheaply enough to
 * call during composition — and, on iOS, without tripping the system's
 * "pasted from" notification, which a real read does.
 *
 * Only meaningful where [needsManualImagePaste] is true; elsewhere the
 * platform delivers pasted images through the field itself and this
 * returns false.
 */
expect fun clipboardHasImage(): Boolean

/**
 * Accept a pasted (or keyboard-inserted) image into the composer's
 * text field and hand it to [onImage] for the same upload+send path
 * drag-drop and the picker use.
 *
 * This is the Android paste-image route: a touch keyboard has no
 * Ctrl+V, and the system Paste action on a plain text field only
 * yields text — so Android registers the field as a rich-content
 * receiver via Compose's `contentReceiver`, which fires for images
 * pasted from the clipboard menu or inserted from the keyboard
 * (Gboard image/GIF). Desktop already handles paste through the
 * Ctrl+V intercept + [readClipboardImageOrNull], so its actual is a
 * no-op.
 *
 * Composable because the Android actual reads `LocalContext` to
 * resolve the pasted `content://` URI to bytes. [enabled] gates it
 * the same way [fileDropTarget] does (`canSend && !uploading`).
 */
@Composable
expect fun Modifier.imagePasteTarget(
    enabled: Boolean,
    onImage: (DroppedFile) -> Unit,
): Modifier
