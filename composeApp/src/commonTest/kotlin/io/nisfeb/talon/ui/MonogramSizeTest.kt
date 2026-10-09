package io.nisfeb.talon.ui

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A default avatar's letters fit its circle whatever the text size. */
class MonogramSizeTest {
    @Test
    fun `the letters shrink with a small text size and never grow past the tile's 40 percent`() {
        assertEquals(14.4f, monogramSp(36.dp, 1f), 0.001f)
        // Rendered size is sp times the font scale: capped at 40% of 36dp.
        assertEquals(14.4f, monogramSp(36.dp, 2f) * 2f, 0.001f)
        assertEquals(14.4f, monogramSp(36.dp, 1.3f) * 1.3f, 0.001f)
        assertEquals(14.4f * 0.85f, monogramSp(36.dp, 0.85f) * 0.85f, 0.001f, "smaller text, smaller letters")
        for (scale in listOf(0.5f, 1f, 1.15f, 1.5f, 3f)) {
            assertTrue(monogramSp(96.dp, scale) * scale <= 96f * 0.4f + 0.001f, "fits at $scale")
        }
    }
}
