package io.nisfeb.talon.login

import io.nisfeb.talon.util.toNSData
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIContext
import platform.CoreImage.CIFilter
import platform.CoreImage.createCGImage
import platform.CoreImage.filterWithName
import platform.CoreImage.outputImage

/**
 * iOS QR generator using CoreImage's `CIQRCodeGenerator`, rendered to a
 * 1px-per-module bitmap whose pixels we read back into the boolean matrix
 * the shared [io.nisfeb.talon.login] QR composable draws.
 *
 * Differences from the ZXing (JVM) generator: CoreImage always emits a
 * quiet-zone border, so the matrix is a few modules larger. That's
 * cosmetic — a wider quiet zone only improves scannability.
 *
 * NOTE(port/ios): this is the single most interop-heavy iOS file and the
 * likeliest to need a nomac build-fix pass (CoreGraphics buffer layout /
 * Y-orientation). The finder-pattern orientation is validated by scanning
 * the generated code on a device.
 */
@OptIn(ExperimentalForeignApi::class)
actual object QrCodeGenerator {
    actual fun generate(content: String): Array<BooleanArray> {
        val message = content.encodeToByteArray().toNSData()
        val filter = CIFilter.filterWithName("CIQRCodeGenerator")
            ?: return arrayOf(BooleanArray(0))
        filter.setValue(message, forKey = "inputMessage")
        filter.setValue("M", forKey = "inputCorrectionLevel")
        val output = filter.outputImage ?: return arrayOf(BooleanArray(0))

        val ciContext = CIContext.context()
        val cgImage = ciContext.createCGImage(output, fromRect = output.extent)
            ?: return arrayOf(BooleanArray(0))

        val w = CGImageGetWidth(cgImage).toInt()
        val h = CGImageGetHeight(cgImage).toInt()
        if (w <= 0 || h <= 0) return arrayOf(BooleanArray(0))

        val bytesPerRow = w * 4
        val buffer = ByteArray(h * bytesPerRow)
        buffer.usePinned { pinned ->
            val colorSpace = CGColorSpaceCreateDeviceRGB()
            val ctx = CGBitmapContextCreate(
                data = pinned.addressOf(0),
                width = w.toULong(),
                height = h.toULong(),
                bitsPerComponent = 8u,
                bytesPerRow = bytesPerRow.toULong(),
                space = colorSpace,
                bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
            )
            CGContextDrawImage(ctx, CGRectMake(0.0, 0.0, w.toDouble(), h.toDouble()), cgImage)
        }

        // CoreGraphics origin is bottom-left, so buffer row 0 is the image's
        // bottom. Flip on read so matrix[0] is the top row — a plain vertical
        // flip would mirror the code and break scanning.
        return Array(h) { y ->
            val srcRow = (h - 1 - y) * bytesPerRow
            BooleanArray(w) { x ->
                // Dark module → low luminance. Read the red channel.
                (buffer[srcRow + x * 4].toInt() and 0xff) < 128
            }
        }
    }
}
