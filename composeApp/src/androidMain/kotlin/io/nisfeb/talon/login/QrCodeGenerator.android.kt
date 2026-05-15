package io.nisfeb.talon.login

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

actual object QrCodeGenerator {
    actual fun generate(content: String): Array<BooleanArray> {
        val hints = mapOf<EncodeHintType, Any>(
            // Margin=0: caller composable adds its own padding around
            // the matrix, and ZXing's default 4-cell "quiet zone" wastes
            // screen real-estate when the QR is already inside a card.
            EncodeHintType.MARGIN to 0,
            // Level M = ~15% error correction. Plenty for a screen-to-
            // camera handoff; bumping higher swells the matrix density
            // (more cells per side) for no real-world gain.
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 1, 1, hints)
        val w = matrix.width
        val h = matrix.height
        return Array(h) { y -> BooleanArray(w) { x -> matrix.get(x, y) } }
    }
}
