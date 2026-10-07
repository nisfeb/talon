package io.nisfeb.talon.compose

import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** The Dock showed Talon 12% taller than the icons beside it (users'
 *  screenshots, 2026-10-07): macOS gets its own file, on Apple's grid. */
class MacIconTest {
    @Test
    fun `the macOS icon is an 824 px tile 100 px in`() {
        val img = assertNotNull(ClassLoader.getSystemResourceAsStream("icon-macos.png")).use { ImageIO.read(it) }
        assertEquals(1024, img.width)
        val drawn = (0 until 1024).filter { x -> (img.getRGB(x, 512) ushr 24) > 0 }
        assertEquals(100 to 923, drawn.first() to drawn.last(), "tile edges across the middle")
    }
}
