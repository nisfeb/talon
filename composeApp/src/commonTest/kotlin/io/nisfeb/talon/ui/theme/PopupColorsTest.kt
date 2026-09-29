package io.nisfeb.talon.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every popup is drawn in the surface colour. Material gives sheets,
 * menus and dialogs a shade each (surfaceContainerLow, surfaceContainer,
 * surfaceContainerHigh), so each kind was a different grey from the
 * others and from the screen under it, and from Talon's own popups,
 * which were already drawn in the surface colour.
 */
class PopupColorsTest {
    private fun assertPopupsAreSurface(scheme: androidx.compose.material3.ColorScheme, which: String) {
        val popups = listOf(scheme.surfaceContainerLow, scheme.surfaceContainer, scheme.surfaceContainerHigh)
        assertEquals(List(3) { scheme.surface }, popups, "$which: sheets, menus and dialogs")
    }

    @Test
    fun `sheets, menus and dialogs share the surface colour in both built-in palettes`() {
        assertPopupsAreSurface(talonColors(darkTheme = false), "light")
        assertPopupsAreSurface(talonColors(darkTheme = true), "dark")
    }

    @Test
    fun `and in a theme of the owner's own`() {
        for (dark in listOf(false, true)) {
            val t = CustomTheme(
                id = "t", name = "Mine", dark = dark, primary = "#FF5500", secondary = "#3355FF",
                tertiary = "#22AA66", background = if (dark) "#101010" else "#F0F0F0", surface = if (dark) "#202020" else "#FAFAFA",
            )
            assertPopupsAreSurface(customScheme(t), if (dark) "custom dark" else "custom light")
        }
    }
}
