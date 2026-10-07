package io.nisfeb.talon.ui

import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A pasted image is drawn straight to at most 4096 px on its longest
 * side: a full-size copy of a very large one ran a Linux user's Talon
 * out of memory, and the process it left behind kept Talon from
 * starting again.
 */
class PastedImageTest {
    @Test
    fun `a size within the limit stands, a larger one is scaled to it with its shape kept`() {
        assertEquals(1920 to 1080, fitWithin(1920, 1080))
        assertEquals(4096 to 4096, fitWithin(4096, 4096))
        assertEquals(4096 to 2048, fitWithin(20000, 10000))
        assertEquals(1024 to 4096, fitWithin(5000, 20000))
        assertEquals(4096 to 1, fitWithin(100000, 3), "never zero")
    }

    @Test
    fun `a large pasted image becomes a PNG at the scaled size`() {
        val big = BufferedImage(6000, 3000, BufferedImage.TYPE_INT_RGB)
        val file = assertNotNull(pngOf(big))
        assertEquals("image/png", file.mimeType)
        val back = ImageIO.read(file.bytes.inputStream())
        assertEquals(4096 to 2048, back.width to back.height)
    }

    @Test
    fun `a small one keeps its size, and an empty one is nothing`() {
        val small = assertNotNull(pngOf(BufferedImage(300, 200, BufferedImage.TYPE_INT_ARGB)))
        val back = ImageIO.read(small.bytes.inputStream())
        assertEquals(300 to 200, back.width to back.height)
        assertNull(pngOf(object : java.awt.Image() {
            override fun getWidth(observer: java.awt.image.ImageObserver?) = -1
            override fun getHeight(observer: java.awt.image.ImageObserver?) = -1
            override fun getSource() = throw UnsupportedOperationException()
            override fun getGraphics() = throw UnsupportedOperationException()
            override fun getProperty(name: String?, observer: java.awt.image.ImageObserver?) = null
        }))
    }

    @Test
    fun `an image too large to read says what to do instead`() {
        assertEquals("That image is too large to paste. Save it as a file and attach it instead.", ImageTooLargeToPaste().message)
    }
}
