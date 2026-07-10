package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.nisfeb.talon.util.toByteArray
import org.jetbrains.skia.Image as SkiaImage
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile

/** Loads `icon.png` from the app bundle (add it to the Xcode target's
 *  resources). Falls back to a transparent painter — exactly the desktop
 *  behaviour when the resource is absent — so a missing asset never
 *  crashes the login screen. */
@Composable
actual fun talonLogoPainter(): Painter {
    val bitmap: ImageBitmap? = remember {
        runCatching {
            val path = NSBundle.mainBundle.pathForResource("icon", "png")
                ?: return@runCatching null
            val data = NSData.dataWithContentsOfFile(path) ?: return@runCatching null
            SkiaImage.makeFromEncoded(data.toByteArray()).toComposeImageBitmap()
        }.getOrNull()
    }
    return if (bitmap != null) BitmapPainter(bitmap)
    else ColorPainter(Color.Transparent)
}
