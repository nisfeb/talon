package io.nisfeb.talon.util

import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation

/**
 * UIImage decodes the image (dropping its metadata) and UIImageJPEGRepresentation
 * writes a JPEG with none. UIImage decodes HEIC and WebP, which is where raw
 * photos with GPS come from on iOS.
 *
 * NOTE: written without an iOS compiler available (K/N can't link iOS on the
 * dev host). Verify on a Mac — in particular the UIImage.imageWithData binding
 * name and the CGFloat quality argument.
 */
internal actual fun reencodeStrippingMetadata(bytes: ByteArray): ByteArray? {
    val img = UIImage.imageWithData(bytes.toNSData()) ?: return null
    val jpeg = UIImageJPEGRepresentation(img, 0.9) ?: return null
    return jpeg.toByteArray()
}
