package io.nisfeb.talon.util

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A picture with its metadata stripped still decodes, the same picture. */
class ImageMetadataDecodeTest {
    private fun picture(format: String): ByteArray {
        val img = BufferedImage(32, 16, BufferedImage.TYPE_INT_RGB)
        img.createGraphics().apply { color = Color.RED; fillRect(0, 0, 32, 16); dispose() }
        return ByteArrayOutputStream().also { ImageIO.write(img, format, it) }.toByteArray()
    }

    private fun decode(b: ByteArray) = ImageIO.read(ByteArrayInputStream(b))!!
    private fun redAt(b: ByteArray) = Color(decode(b).getRGB(16, 8)).let { it.red > 200 && it.green < 60 && it.blue < 60 }

    @Test
    fun `a JPEG decodes the same without its EXIF`() {
        val plain = picture("jpg")
        val gps = "Exif\u0000\u0000II*\u0000\u0008\u0000\u0000\u0000\u0000\u0000GPS 40.7N 74.0W".encodeToByteArray()
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0, (gps.size + 2).toByte()) + gps
        val tagged = plain.copyOfRange(0, 2) + app1 + plain.copyOfRange(2, plain.size)
        val out = stripImageMetadata(tagged)
        assertTrue("GPS" !in out.decodeToString(), "the location is gone")
        assertEquals(32 to 16, decode(out).let { it.width to it.height })
        assertTrue(redAt(out), "and it is the same picture")
    }

    // A format the byte-level strip doesn't parse (here a BMP, standing in for
    // WebP/HEIC) is decoded and re-encoded to a metadata-free JPEG rather than
    // passed through with its metadata intact.
    @Test
    fun `an unparsed format is re-encoded to a stripped JPEG`() {
        val bmp = picture("bmp")
        assertTrue(
            bmp[0] == 'B'.code.toByte() && bmp[1] == 'M'.code.toByte(),
            "the input is a BMP, which the byte-level strip does not handle",
        )
        val out = stripImageMetadata(bmp)
        assertTrue(
            out[0] == 0xFF.toByte() && out[1] == 0xD8.toByte() && out[2] == 0xFF.toByte(),
            "it came back as a JPEG (re-encoded), not the original BMP",
        )
        assertEquals(32 to 16, decode(out).let { it.width to it.height })
        assertTrue(redAt(out), "and it is the same picture")
    }

    @Test
    fun `a PNG decodes the same without its text`() {
        val plain = picture("png")
        val data = "Author\u0000Jane Doe".encodeToByteArray()
        val type = "tEXt".encodeToByteArray()
        val crc = CRC32().apply { update(type); update(data) }.value
        val text = byteArrayOf(0, 0, 0, data.size.toByte()) + type + data +
            byteArrayOf((crc shr 24).toByte(), (crc shr 16).toByte(), (crc shr 8).toByte(), crc.toByte())
        // After IHDR: 8 bytes of signature and 25 of IHDR.
        val tagged = plain.copyOfRange(0, 33) + text + plain.copyOfRange(33, plain.size)
        assertEquals(32, decode(tagged).width, "the tagged one is a good PNG to begin with")
        val out = stripImageMetadata(tagged)
        assertTrue("Jane" !in out.decodeToString())
        assertEquals(32 to 16, decode(out).let { it.width to it.height })
        assertTrue(redAt(out))
    }
}
