package io.nisfeb.talon.login

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

actual object QrCodeGenerator {
    actual fun generate(content: String): Array<BooleanArray> {
        val hints = mapOf<EncodeHintType, Any>(
            EncodeHintType.MARGIN to 0,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 1, 1, hints)
        val w = matrix.width
        val h = matrix.height
        return Array(h) { y -> BooleanArray(w) { x -> matrix.get(x, y) } }
    }
}
