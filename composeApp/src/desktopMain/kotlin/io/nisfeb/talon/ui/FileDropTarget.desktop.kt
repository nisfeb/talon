package io.nisfeb.talon.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import io.nisfeb.talon.util.Log
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.net.URLConnection
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

private const val TAG = "FileDropTarget"

@OptIn(
    ExperimentalComposeUiApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)
actual fun Modifier.fileDropTarget(
    enabled: Boolean,
    onFiles: (List<DroppedFile>) -> Unit,
): Modifier = composed {
    // Stable holder so the DragAndDropTarget instance can capture
    // the latest callback without re-creating the modifier on every
    // recomposition (which would tear down the AWT-side listener
    // mid-drag).
    val callback = remember { CallbackHolder() }
    callback.onFiles = onFiles
    val target = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                // The transferable is only valid during this callback,
                // so extract the (cheap) file list here on the AWT
                // event thread — but push the actual disk reads onto a
                // background thread: readBytes() on a large drop would
                // otherwise freeze painting for the whole read.
                val list = extractFileList(event)
                if (list.isEmpty()) return false
                // Pin delivery to the conversation that RECEIVED the
                // drop: the holder is rebound on every recomposition,
                // and switching chats while a large read runs would
                // otherwise hand these files to the new conversation's
                // lambda — auto-sending them into the wrong room.
                val deliver = callback.onFiles
                Thread {
                    val files = list.mapNotNull { readDroppedFile(it) }
                    if (files.isNotEmpty()) {
                        SwingUtilities.invokeLater { deliver(files) }
                    }
                }.apply {
                    isDaemon = true
                    name = "Talon-drop-read"
                }.start()
                return true
            }
        }
    }
    dragAndDropTarget(
        shouldStartDragAndDrop = { _: DragAndDropEvent -> enabled },
        target = target,
    )
}

private class CallbackHolder {
    @Volatile var onFiles: (List<DroppedFile>) -> Unit = {}
}

@OptIn(ExperimentalComposeUiApi::class)
private fun extractFileList(event: DragAndDropEvent): List<File> {
    val transferable = runCatching { event.awtTransferable }.getOrElse {
        Log.w(TAG, "no awtTransferable on drop event: ${it.message}")
        return emptyList()
    }
    if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
        Log.w(TAG, "drop ignored: transferable does not advertise javaFileListFlavor")
        return emptyList()
    }
    @Suppress("UNCHECKED_CAST")
    return runCatching {
        transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>
    }.getOrElse {
        Log.w(TAG, "getTransferData(javaFileListFlavor) failed: ${it.message}")
        return emptyList()
    } ?: emptyList()
}

private fun readDroppedFile(file: File): DroppedFile? {
    if (!file.isFile) {
        Log.w(TAG, "drop target is not a regular file: ${file.absolutePath}")
        return null
    }
    val bytes = runCatching { file.readBytes() }.getOrElse {
        Log.w(TAG, "read failed for ${file.absolutePath}: ${it.message}")
        return null
    }
    val mime = URLConnection.guessContentTypeFromName(file.name)
        ?: "application/octet-stream"
    return DroppedFile(name = file.name, mimeType = mime, bytes = bytes)
}

actual fun readClipboardImageOrNull(): DroppedFile? {
    val clipboard = runCatching { Toolkit.getDefaultToolkit().systemClipboard }
        .getOrNull() ?: return null
    val contents = runCatching { clipboard.getContents(null) }.getOrNull() ?: return null
    if (!contents.isDataFlavorSupported(DataFlavor.imageFlavor)) return null
    // AWT hands back the image already decoded, at full size. One too
    // large for that runs out of memory here, and is said so: a crash in
    // the key handler took the window and left the process holding the
    // single-instance lock, so Talon would not start again.
    val img = try {
        contents.getTransferData(DataFlavor.imageFlavor) as? java.awt.Image
    } catch (e: OutOfMemoryError) {
        throw ImageTooLargeToPaste()
    } catch (t: Throwable) {
        null
    } ?: return null
    return try {
        pngOf(img)
    } catch (e: OutOfMemoryError) {
        throw ImageTooLargeToPaste()
    }
}

/**
 * The longest side a pasted image keeps. Plenty for a chat, and a
 * full-size copy of a very large one was what ran the app out of
 * memory: a 20000 px square is 1.6 GB as pixels.
 */
internal const val MAX_PASTE_SIDE = 4096

/** [width] by [height] scaled to fit within [maxSide] on its longest side, aspect kept; as is when it fits. */
internal fun fitWithin(width: Int, height: Int, maxSide: Int = MAX_PASTE_SIDE): Pair<Int, Int> {
    val longest = maxOf(width, height)
    if (longest <= maxSide) return width to height
    val scale = maxSide.toDouble() / longest
    return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
}

/**
 * [img] as a PNG, drawn straight to its scaled size (see [fitWithin]):
 * never a second full-size copy. ImageIO writes only a BufferedImage,
 * which is why it is drawn at all. Null when it cannot be encoded.
 */
internal fun pngOf(img: java.awt.Image, maxSide: Int = MAX_PASTE_SIDE): DroppedFile? {
    val width = img.getWidth(null)
    val height = img.getHeight(null)
    if (width <= 0 || height <= 0) return null
    val (w, h) = fitWithin(width, height, maxSide)
    val buffered = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    val g = buffered.createGraphics()
    try {
        if (w != width) {
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        }
        g.drawImage(img, 0, 0, w, h, null)
    } finally {
        g.dispose()
    }
    val out = java.io.ByteArrayOutputStream()
    val ok = runCatching { ImageIO.write(buffered, "png", out) }.getOrElse { false }
    if (ok != true) {
        Log.w(TAG, "ImageIO.write failed for clipboard image (${width}x$height)")
        return null
    }
    if (w != width) Log.i(TAG, "pasted image scaled from ${width}x$height to ${w}x$h")
    return DroppedFile(
        name = "pasted-${System.currentTimeMillis()}.png",
        mimeType = "image/png",
        bytes = out.toByteArray(),
    )
}

// Desktop pastes images through the Ctrl+V intercept in ChatComposer
// (which calls readClipboardImageOrNull above), so the rich-content
// receiver is a no-op here.
@androidx.compose.runtime.Composable
actual fun Modifier.imagePasteTarget(
    enabled: Boolean,
    onImage: (DroppedFile) -> Unit,
): Modifier = this

// Images arrive through the field itself here (content receiver
// on Android, the Ctrl+V intercept on desktop), so the composer
// never needs to offer a manual paste action.
actual fun clipboardHasImage(): Boolean = runCatching {
    Toolkit.getDefaultToolkit().systemClipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)
}.getOrDefault(false)
