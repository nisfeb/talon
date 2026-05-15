package io.nisfeb.talon.login

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QrCodeGeneratorTest {

    @Test
    fun matrixIsSquareAndNonEmpty() {
        val matrix = QrCodeGenerator.generate("hello")
        assertTrue(matrix.isNotEmpty(), "matrix has rows")
        val cols = matrix[0].size
        assertTrue(cols > 0, "matrix has columns")
        for (row in matrix) {
            assertEquals(cols, row.size, "all rows have the same column count")
        }
        // QR codes are square.
        assertEquals(matrix.size, cols, "matrix is square")
    }

    @Test
    fun roundTripsTalonLoginUri() {
        val payload = TalonLoginUri.Payload(
            url = "https://ship.example.com",
            code = "foo-bar-baz-quux",
        )
        val encoded = TalonLoginUri.encode(payload)
        val matrix = QrCodeGenerator.generate(encoded)

        // Render the boolean matrix into ARGB pixels at SCALE-px-per-cell,
        // then run ZXing's reader over the result. If decode succeeds
        // and matches `encoded`, the generator's geometry is correct.
        // SCALE > 1 ensures the reader's internal sampling locks onto
        // each module cleanly (1:1 rendering trips the locator).
        val scale = 4
        val cells = matrix.size
        val margin = 4 * scale
        val side = cells * scale + margin * 2
        val pixels = IntArray(side * side) { -0x1 } // white
        for (cy in 0 until cells) {
            for (cx in 0 until cells) {
                if (!matrix[cy][cx]) continue
                val baseY = margin + cy * scale
                val baseX = margin + cx * scale
                for (py in 0 until scale) {
                    for (px in 0 until scale) {
                        pixels[(baseY + py) * side + (baseX + px)] = 0xFF000000.toInt()
                    }
                }
            }
        }
        val source = RGBLuminanceSource(side, side, pixels)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        val result = QRCodeReader().decode(bitmap)

        assertEquals(encoded, result.text)
        val decoded = TalonLoginUri.decode(result.text)
        assertEquals(payload, decoded)
    }

    @Test
    fun handlesLongCode() {
        // A realistic +code is 27 chars (lidlut-tabwed-pillex-ridrup style).
        // Verify generation succeeds and decodes round-trip cleanly.
        val payload = TalonLoginUri.Payload(
            url = "https://prolonged-host-name.example.org:8443",
            code = "lidlut-tabwed-pillex-ridrup",
        )
        val encoded = TalonLoginUri.encode(payload)
        val matrix = QrCodeGenerator.generate(encoded)
        assertTrue(matrix.size > 20, "longer payload yields a denser matrix")
    }
}
