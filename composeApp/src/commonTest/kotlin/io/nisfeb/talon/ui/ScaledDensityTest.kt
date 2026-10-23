package io.nisfeb.talon.ui

import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the size setting does to the window: zoom on desktop, text alone on phones. */
class ScaledDensityTest {
    private val base = Density(density = 2f, fontScale = 1.1f)

    @Test
    fun `zoom scales everything and leaves the text to the preset`() {
        val d = scaledDensity(base, ChatDensity.Cozy, userScale = 1.5f, wholeWindow = true)
        assertEquals(3f, d.density, 0.0001f)
        assertEquals(1.1f * 1.12f, d.fontScale, 0.0001f)
    }

    @Test
    fun `text size scales the text alone`() {
        val d = scaledDensity(base, ChatDensity.Cozy, userScale = 1.5f, wholeWindow = false)
        assertEquals(2f, d.density, 0.0001f)
        assertEquals(1.1f * 1.12f * 1.5f, d.fontScale, 0.0001f)
    }

    @Test
    fun `at 100 percent both are the base and the preset`() {
        for (whole in listOf(true, false)) {
            val d = scaledDensity(base, ChatDensity.Compact, userScale = 1f, wholeWindow = whole)
            assertEquals(2f, d.density, 0.0001f)
            assertEquals(1.1f * 0.90f, d.fontScale, 0.0001f)
        }
    }
}
