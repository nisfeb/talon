package io.nisfeb.talon.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * BitmapFactory decodes to a Bitmap (metadata does not survive), and a JPEG
 * compressed from a Bitmap carries no EXIF/GPS. BitmapFactory decodes WebP on
 * all supported API levels and HEIC on API 28+; an older HEIC decode returns
 * null and the caller passes the original through.
 */
internal actual fun reencodeStrippingMetadata(bytes: ByteArray): ByteArray? = runCatching {
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    val out = ByteArrayOutputStream()
    if (!bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)) return null
    out.toByteArray()
}.getOrNull()
