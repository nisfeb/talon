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

actual fun decodeImageDimensions(bytes: ByteArray): Pair<Int, Int>? = runCatching {
    ByteArrayInputStream(bytes).use { stream ->
        val img = ImageIO.read(stream) ?: return null
        if (img.width <= 0 || img.height <= 0) return null
        img.width to img.height
    }
}.getOrNull()
