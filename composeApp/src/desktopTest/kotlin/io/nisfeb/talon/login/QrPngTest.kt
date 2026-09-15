package io.nisfeb.talon.login

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/** The saved image is a real PNG, and a phone reading it gets the link back. */
class QrPngTest {
    @Test
    fun `a saved code decodes back to its link`() {
        val link = "talon://invite/~sampel-palnet"
        val matrix = QrCodeGenerator.generate(link)
        val img = ImageIO.read(ByteArrayInputStream(qrPng(matrix, scale = 4, border = 2)))
        assertEquals((matrix.size + 4) * 4, img.width)
        assertEquals(0xFFFFFF, img.getRGB(0, 0) and 0xFFFFFF, "the quiet zone is white")
        val pixels = IntArray(img.width * img.height).also { img.getRGB(0, 0, img.width, img.height, it, 0, img.width) }
        val read = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(img.width, img.height, pixels))))
        assertEquals(link, read.text)
    }
}
