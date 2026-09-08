package io.nisfeb.talon.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import io.nisfeb.talon.ui.parseHexColor
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A user-made theme: five colors the user picks, everything else
 * derived. Hex strings so it serializes the same way everywhere and
 * survives a hand edit on the ship.
 */
@Serializable
data class CustomTheme(
    val id: String,
    val name: String,
    val dark: Boolean,
    val primary: String,
    val secondary: String,
    val tertiary: String,
    val background: String,
    val surface: String,
) {
    val valid: Boolean
        get() = name.isNotBlank() &&
            listOf(primary, secondary, tertiary, background, surface).all { parseHexColor(it) != null }

    companion object {
        /** A fresh theme seeded from the built-in palette for [dark]. */
        fun blank(dark: Boolean, id: String): CustomTheme {
            val base = talonColors(dark)
            return CustomTheme(
                id = id, name = "", dark = dark,
                primary = base.primary.hex(), secondary = base.secondary.hex(), tertiary = base.tertiary.hex(),
                background = base.background.hex(), surface = base.surface.hex(),
            )
        }
    }
}

/** The saved themes and which one is on; null means the built-in theme. */
@Serializable
data class ThemeSettings(
    val themes: List<CustomTheme> = emptyList(),
    val activeId: String? = null,
) {
    val active: CustomTheme? get() = themes.firstOrNull { it.id == activeId }

    fun toJson(): String = JSON.encodeToString(this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }
        fun fromJson(text: String?): ThemeSettings? =
            text?.takeIf { it.isNotBlank() }?.let { runCatching { JSON.decodeFromString<ThemeSettings>(it) }.getOrNull() }
    }
}

fun Color.hex(): String = "#" + (toArgb() and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()

/**
 * Builds a full scheme from the five picked colors. On-colors come
 * from luminance, containers and the surface ramp from blending
 * toward the background, so a theme reads well without the user
 * having to understand forty Material roles.
 */
fun customScheme(t: CustomTheme): ColorScheme {
    val base = talonColors(t.dark)
    val primary = parseHexColor(t.primary) ?: base.primary
    val secondary = parseHexColor(t.secondary) ?: base.secondary
    val tertiary = parseHexColor(t.tertiary) ?: base.tertiary
    val background = parseHexColor(t.background) ?: base.background
    val surface = parseHexColor(t.surface) ?: base.surface
    val ink = Color(0xFF1C1917)
    val paper = Color(0xFFFAFAF9)
    fun on(c: Color) = if (c.luminance() > 0.4f) ink else paper
    val toward = if (t.dark) Color.White else Color.Black
    fun container(c: Color) = if (t.dark) lerp(c, background, 0.6f) else lerp(c, Color.White, 0.8f)
    fun onContainer(c: Color) = if (t.dark) lerp(c, Color.White, 0.75f) else lerp(c, Color.Black, 0.65f)
    val onSurface = on(surface)
    val onBackground = on(background)
    val muted = lerp(onSurface, surface, 0.35f)
    return base.copy(
        primary = primary, onPrimary = on(primary),
        primaryContainer = container(primary), onPrimaryContainer = onContainer(primary),
        secondary = secondary, onSecondary = on(secondary),
        secondaryContainer = container(secondary), onSecondaryContainer = onContainer(secondary),
        tertiary = tertiary, onTertiary = on(tertiary),
        tertiaryContainer = container(tertiary), onTertiaryContainer = onContainer(tertiary),
        background = background, onBackground = onBackground,
        surface = surface, onSurface = onSurface,
        surfaceVariant = lerp(surface, toward, 0.06f), onSurfaceVariant = muted,
        outline = lerp(surface, onSurface, 0.4f), outlineVariant = lerp(surface, onSurface, 0.15f),
        surfaceTint = muted,
        surfaceDim = if (t.dark) background else lerp(surface, Color.Black, 0.08f),
        surfaceBright = if (t.dark) lerp(surface, Color.White, 0.12f) else Color.White,
        surfaceContainerLowest = if (t.dark) lerp(surface, Color.Black, 0.3f) else Color.White,
        surfaceContainerLow = lerp(surface, toward, 0.03f),
        surfaceContainer = lerp(surface, toward, 0.06f),
        surfaceContainerHigh = lerp(surface, toward, 0.09f),
        surfaceContainerHighest = lerp(surface, toward, 0.12f),
        inverseSurface = if (t.dark) paper else ink,
        inverseOnSurface = if (t.dark) ink else paper,
        inversePrimary = lerp(primary, if (t.dark) Color.Black else Color.White, 0.3f),
    )
}
