package io.nisfeb.talon.ui

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class GalleryShapeTest {
    @Test
    fun `one camera is one tile`() = assertEquals(1 to 1, galleryShape(1, 800.dp, 600.dp, 6.dp))

    @Test
    fun `two sit side by side in a wide space and stack in a tall one`() {
        assertEquals(2 to 1, galleryShape(2, 1000.dp, 400.dp, 6.dp))
        assertEquals(1 to 2, galleryShape(2, 400.dp, 800.dp, 6.dp))
    }

    @Test
    fun `a strip lays them in one row`() = assertEquals(3 to 1, galleryShape(3, 1200.dp, 300.dp, 6.dp))

    @Test
    fun `four make a square, and five take the shape with the larger tiles`() {
        assertEquals(2 to 2, galleryShape(4, 1000.dp, 600.dp, 6.dp))
        assertEquals(3 to 2, galleryShape(5, 1600.dp, 600.dp, 6.dp))
        // Narrower, two columns of three beat three of two.
        assertEquals(2 to 3, galleryShape(5, 1000.dp, 600.dp, 6.dp))
    }
}
