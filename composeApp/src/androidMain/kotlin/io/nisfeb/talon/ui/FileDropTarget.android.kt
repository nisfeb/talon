package io.nisfeb.talon.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

/**
 * External file-drag drop: still a no-op on touch Android (no
 * Compose-friendly external-drag surface for a file manager).
 * Image paste IS now wired — see [imagePasteTarget] below.
 */
actual fun Modifier.fileDropTarget(
    enabled: Boolean,
    onFiles: (List<DroppedFile>) -> Unit,
): Modifier = this

// Pull-style clipboard read is unused on Android — paste flows through
// the contentReceiver in imagePasteTarget instead, which fires on the
// actual paste/insert event and already has the URI in hand.
actual fun readClipboardImageOrNull(): DroppedFile? = null

@OptIn(ExperimentalFoundationApi::class)
@Composable
actual fun Modifier.imagePasteTarget(
    enabled: Boolean,
    onImage: (DroppedFile) -> Unit,
): Modifier {
    if (!enabled) return this
    val context = LocalContext.current
    val listener = object : ReceiveContentListener {
        override fun onReceive(
            transferableContent: TransferableContent,
        ): TransferableContent? {
            // Let anything that isn't an image fall through to the text
            // field's default handling (plain-text paste, etc).
            if (!transferableContent.hasMediaType(MediaType.Image)) {
                return transferableContent
            }
            val resolver = context.contentResolver
            return transferableContent.consume { item ->
                val uri = item.uri ?: return@consume false
                val mime = resolver.getType(uri) ?: return@consume false
                if (!mime.startsWith("image/")) return@consume false
                val bytes = runCatching {
                    resolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull() ?: return@consume false
                val ext = mime.substringAfterLast('/', "img")
                onImage(
                    DroppedFile(
                        name = "pasted-image.$ext",
                        mimeType = mime,
                        bytes = bytes,
                    ),
                )
                true
            }
        }
    }
    return this.contentReceiver(listener)
}
