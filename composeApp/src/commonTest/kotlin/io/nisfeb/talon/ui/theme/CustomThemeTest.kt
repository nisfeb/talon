package io.nisfeb.talon.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CustomThemeTest {
    private val ocean = CustomTheme("a1", "Ocean", dark = true, primary = "#38BDF8", secondary = "#A78BFA",
        tertiary = "#34D399", background = "#0B1120", surface = "#111827")

    @Test
    fun onColorsReadAgainstTheirSurfaces() {
        val s = customScheme(ocean)
        fun contrast(a: Color, b: Color): Float {
            val l1 = a.luminance() + 0.05f
            val l2 = b.luminance() + 0.05f
            return maxOf(l1, l2) / minOf(l1, l2)
        }
        assertTrue(contrast(s.onPrimary, s.primary) > 3f)
        assertTrue(contrast(s.onSurface, s.surface) > 7f)
        assertTrue(contrast(s.onBackground, s.background) > 7f)
        assertTrue(contrast(s.onPrimaryContainer, s.primaryContainer) > 3f)
        assertEquals(Color(0xFF38BDF8), s.primary)
        assertFalse(s.surfaceContainerHigh == s.surface, "the surface ramp must step")
    }

    @Test
    fun settingsRoundTripAndValidate() {
        val settings = ThemeSettings(listOf(ocean), activeId = "a1")
        assertEquals(settings, ThemeSettings.fromJson(settings.toJson()))
        assertEquals(ocean, settings.active)
        assertNull(ThemeSettings.fromJson("not json"))
        assertNull(ThemeSettings.fromJson(""))
        assertFalse(ocean.copy(primary = "blue").valid)
        assertFalse(ocean.copy(name = " ").valid)
        assertTrue(CustomTheme.blank(dark = false, id = "x").copy(name = "Paper").valid)
        assertEquals("#38BDF8", Color(0xFF38BDF8).hex())
    }
}
