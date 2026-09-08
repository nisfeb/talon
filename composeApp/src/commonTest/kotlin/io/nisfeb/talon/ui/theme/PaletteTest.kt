package io.nisfeb.talon.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PaletteTest {
    /** Material's baseline surface roles are lavender; ours must be the stone palette in both modes. */
    @Test
    fun surfaceRolesAreOurs() {
        val light = talonColors(false)
        val dark = talonColors(true)
        for ((ours, baseline) in listOf(light to lightColorScheme(), dark to darkColorScheme())) {
            assertNotEquals(baseline.surfaceContainer, ours.surfaceContainer)
            assertNotEquals(baseline.surfaceContainerHigh, ours.surfaceContainerHigh)
            assertNotEquals(baseline.surfaceTint, ours.surfaceTint)
            assertNotEquals(ours.primary, ours.surfaceTint, "elevation must not wash surfaces in the accent")
        }
        assertEquals(Color(0xFFF5F5F4), light.surfaceContainer)
        assertEquals(Color(0xFFFAFAF9), light.background)
    }
}
