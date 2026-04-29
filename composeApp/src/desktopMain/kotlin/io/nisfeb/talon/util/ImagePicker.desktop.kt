package io.nisfeb.talon.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

@Composable
actual fun rememberImagePicker(): suspend () -> PickedImage? {
    val picker = remember { DesktopFilePicker() }
    return remember(picker) {
        suspend { picker.pickImage() }
    }
}

@Composable
actual fun rememberAnyFilePicker(): suspend () -> PickedImage? {
    val picker = remember { DesktopFilePicker() }
    return remember(picker) {
        suspend { picker.pickAnyFile() }
    }
}

/**
 * Header-only width/height. Avoids ImageIO.read's full-bitmap decode
 * — for a 20MB photo that's a multi-second hitch and a ~100MB heap
 * spike. ImageReader.getWidth/getHeight only read enough of the
 * stream to satisfy the call.
 */
actual fun decodeImageDimensions(bytes: ByteArray): Pair<Int, Int>? = runCatching {
    ByteArrayInputStream(bytes).use { stream ->
        ImageIO.createImageInputStream(stream).use { iis ->
            val readers = ImageIO.getImageReaders(iis)
            if (!readers.hasNext()) return null
            val reader = readers.next()
            try {
                reader.input = iis
                val w = reader.getWidth(0)
                val h = reader.getHeight(0)
                if (w <= 0 || h <= 0) null else w to h
            } finally {
                reader.dispose()
            }
        }
    }
}.getOrNull()
