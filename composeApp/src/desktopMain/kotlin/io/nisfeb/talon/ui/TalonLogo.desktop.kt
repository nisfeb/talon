package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image as SkiaImage

/**
 * Decodes /icon.png from the classpath via Skia and caches the
 * resulting ImageBitmap for the Composable's lifetime. Falls back
 * to a transparent ColorPainter if loading fails — the logo's a
 * brand mark, not safety-critical.
 */
@Composable
actual fun talonLogoPainter(): Painter {
    val bitmap: ImageBitmap? = remember {
        runCatching {
            val bytes = ClassLoader.getSystemResourceAsStream("icon.png")
                ?.use { it.readBytes() }
                ?: return@runCatching null
            SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
        }.getOrNull()
    }
    return if (bitmap != null) BitmapPainter(bitmap)
    else ColorPainter(androidx.compose.ui.graphics.Color.Transparent)
}
