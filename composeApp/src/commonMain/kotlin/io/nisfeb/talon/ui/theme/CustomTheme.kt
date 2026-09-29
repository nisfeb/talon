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
 * A user-made theme: five colors the user picks, and six more they may,
 * everything else derived. Hex strings so it serializes the same way
 * everywhere and survives a hand edit on the ship. Furum and lattice
 * read the same object and draw with the same rules.
 *
 * Each of the six extras is "#RRGGBB", "" for derived, or null where the
 * writer did not know the key: a Talon from before them, or a tool that
 * only writes the five. Talon writes all six, so a missing one is never
 * a choice, and [keepingLocalExtras] keeps this device's.
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
    /** Body text on the background and surfaces. */
    val text: String? = null,
    /** Secondary text: timestamps, captions, hints. */
    val muted: String? = null,
    /** Raised surfaces: cards, chips, link previews. */
    val raised: String? = null,
    /** Errors and destructive actions. */
    val error: String? = null,
    /** The highlight on selected text. */
    val selection: String? = null,
    /** Links and mentions; derived is the standard link blue, not a theme colour. */
    val link: String? = null,
) {
    val valid: Boolean
        get() = name.isNotBlank() &&
            listOf(primary, secondary, tertiary, background, surface).all { parseHexColor(it) != null } &&
            extras.all { it.isNullOrEmpty() || parseHexColor(it) != null }

    private val extras get() = listOf(text, muted, raised, error, selection, link)

    /** With every extra written: "" for derived. What Talon saves. */
    fun explicit(): CustomTheme = copy(
        text = text.orEmpty(), muted = muted.orEmpty(), raised = raised.orEmpty(),
        error = error.orEmpty(), selection = selection.orEmpty(), link = link.orEmpty(),
    )

    /** This theme with [local]'s value for each extra the writer did not know. */
    fun keepingLocalExtras(local: CustomTheme): CustomTheme = copy(
        text = text ?: local.text, muted = muted ?: local.muted, raised = raised ?: local.raised,
        error = error ?: local.error, selection = selection ?: local.selection, link = link ?: local.link,
    )

    companion object {
        /** A fresh theme seeded from the built-in palette for [dark]. */
        fun blank(dark: Boolean, id: String): CustomTheme {
            val base = talonColors(dark)
            return CustomTheme(
                id = id, name = "", dark = dark,
                primary = base.primary.hex(), secondary = base.secondary.hex(), tertiary = base.tertiary.hex(),
                background = base.background.hex(), surface = base.surface.hex(),
            ).explicit()
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

    /**
     * Settings from another device, keeping this device's extras where a
     * theme arrived without them: an older Talon, or a tool that writes
     * only the five, drops the keys it does not know, and absent is not
     * a choice to clear one ("" is).
     */
    fun keepingLocalExtras(local: ThemeSettings): ThemeSettings = copy(
        themes = themes.map { t -> local.themes.firstOrNull { it.id == t.id }?.let(t::keepingLocalExtras) ?: t },
    )

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }
        fun fromJson(text: String?): ThemeSettings? =
            text?.takeIf { it.isNotBlank() }?.let { runCatching { JSON.decodeFromString<ThemeSettings>(it) }.getOrNull() }
    }
}

fun Color.hex(): String = "#" + (toArgb() and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()

/** The standard link blue: links and mentions, in every theme that sets no link colour. */
val LINK_BLUE = Color(0xFF2962FF)

/** An extra's colour where it is set; null where it is derived ("" or absent) or unreadable. */
private fun extra(hex: String?): Color? = hex?.takeIf { it.isNotEmpty() }?.let(::parseHexColor)

/** The theme's link colour where it sets one; null keeps the standard link blue. */
fun CustomTheme.linkColor(): Color? = extra(link)

/** The theme's selection highlight where it sets one; null keeps Material's, from primary. */
fun CustomTheme.selectionColor(): Color? = extra(selection)

/**
 * Builds a full scheme from the five picked colors, and the extras
 * where they are set. On-colors come from luminance, containers and
 * the surface ramp from blending toward the background, so a theme
 * reads well without the user having to understand forty Material
 * roles. What is derived from an extra's colour is derived from the
 * extra where it is set: muted text and the outlines from [CustomTheme.text].
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
    val text = extra(t.text)
    val onSurface = text ?: on(surface)
    val onBackground = text ?: on(background)
    val muted = extra(t.muted) ?: lerp(onSurface, surface, 0.35f)
    val raised = extra(t.raised)
    val error = extra(t.error)
    return base.copy(
        primary = primary, onPrimary = on(primary),
        primaryContainer = container(primary), onPrimaryContainer = onContainer(primary),
        secondary = secondary, onSecondary = on(secondary),
        secondaryContainer = container(secondary), onSecondaryContainer = onContainer(secondary),
        tertiary = tertiary, onTertiary = on(tertiary),
        tertiaryContainer = container(tertiary), onTertiaryContainer = onContainer(tertiary),
        background = background, onBackground = onBackground,
        surface = surface, onSurface = onSurface,
        surfaceVariant = raised ?: lerp(surface, toward, 0.06f), onSurfaceVariant = muted,
        outline = lerp(surface, onSurface, 0.4f), outlineVariant = lerp(surface, onSurface, 0.15f),
        surfaceTint = muted,
        surfaceDim = if (t.dark) background else lerp(surface, Color.Black, 0.08f),
        surfaceBright = if (t.dark) lerp(surface, Color.White, 0.12f) else Color.White,
        surfaceContainerLowest = if (t.dark) lerp(surface, Color.Black, 0.3f) else Color.White,
        // Popups in the surface colour, as the built-in palettes draw them.
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surface,
        surfaceContainerHighest = raised ?: lerp(surface, toward, 0.12f),
        inverseSurface = if (t.dark) paper else ink,
        inverseOnSurface = if (t.dark) ink else paper,
        inversePrimary = lerp(primary, if (t.dark) Color.Black else Color.White, 0.3f),
        error = error ?: base.error, onError = error?.let(::on) ?: base.onError,
        errorContainer = error?.let(::container) ?: base.errorContainer,
        onErrorContainer = error?.let(::onContainer) ?: base.onErrorContainer,
    )
}
