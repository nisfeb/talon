package io.nisfeb.talon.util

import java.awt.Color
import java.awt.Transparency
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * ImageIO decodes to a BufferedImage (no metadata survives the decode) and its
 * JPEG writer emits no EXIF/XMP, so the result carries none. A JPEG can't hold
 * alpha, so a transparent source is flattened onto white first.
 *
 * Limitation: stock ImageIO has no WebP or HEIC reader, so those return null
 * here and the caller passes the original through. Android (BitmapFactory) and
 * iOS (UIImage) do decode them, which is where raw HEIC/WebP photos actually
 * come from; a WebP/HEIC file attached on desktop is the uncovered edge.
 */
internal actual fun reencodeStrippingMetadata(bytes: ByteArray): ByteArray? = runCatching {
    val img = ByteArrayInputStream(bytes).use { ImageIO.read(it) } ?: return null
    val opaque = if (img.transparency == Transparency.OPAQUE) {
        img
    } else {
        BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB).also { rgb ->
            val g = rgb.createGraphics()
            g.color = Color.WHITE
            g.fillRect(0, 0, img.width, img.height)
            g.drawImage(img, 0, 0, null)
            g.dispose()
        }
    }
    val out = ByteArrayOutputStream()
    if (!ImageIO.write(opaque, "jpg", out)) return null
    out.toByteArray()
}.getOrNull()
