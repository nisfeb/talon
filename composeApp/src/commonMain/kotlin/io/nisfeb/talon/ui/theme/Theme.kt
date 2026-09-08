package io.nisfeb.talon.ui.theme


import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

// Brand palette — see DESIGN.md for the full rationale. Do not hardcode
// these hexes in composables; always go through MaterialTheme.colorScheme.
private val LightColors = lightColorScheme(
    primary              = Color(0xFFF59E0B),
    onPrimary            = Color(0xFF1C1917),
    primaryContainer     = Color(0xFFFEF3C7),
    onPrimaryContainer   = Color(0xFF78350F),

    secondary            = Color(0xFF4338CA),
    onSecondary          = Color(0xFFFFFFFF),
    secondaryContainer   = Color(0xFFE0E7FF),
    onSecondaryContainer = Color(0xFF1E1B4B),

    tertiary             = Color(0xFF059669),
    onTertiary           = Color(0xFFFFFFFF),
    tertiaryContainer    = Color(0xFFD1FAE5),
    onTertiaryContainer  = Color(0xFF064E3B),

    background           = Color(0xFFFAFAF9),
    onBackground         = Color(0xFF1C1917),
    surface              = Color(0xFFFFFFFF),
    onSurface            = Color(0xFF1C1917),
    surfaceVariant       = Color(0xFFF5F5F4),
    onSurfaceVariant     = Color(0xFF57534E),

    outline              = Color(0xFFA8A29E),
    outlineVariant       = Color(0xFFE7E5E4),
    error                = Color(0xFFDC2626),
    onError              = Color(0xFFFFFFFF),
    errorContainer       = Color(0xFFFEE2E2),
    onErrorContainer     = Color(0xFF7F1D1D),
    // Material 3's newer surface roles. Unset, they fall back to the
    // baseline lavender-tinted greys and an amber surfaceTint, which
    // put purple top bars, cards, menus and dialogs, and amber-washed
    // elevated surfaces, on top of this warm stone palette in light mode.
    surfaceTint          = Color(0xFF57534E),
    surfaceDim           = Color(0xFFE7E5E4),
    surfaceBright        = Color(0xFFFFFFFF),
    surfaceContainerLowest  = Color(0xFFFFFFFF),
    surfaceContainerLow     = Color(0xFFFAFAF9),
    surfaceContainer        = Color(0xFFF5F5F4),
    surfaceContainerHigh    = Color(0xFFEEEDEC),
    surfaceContainerHighest = Color(0xFFE7E5E4),
    inverseSurface       = Color(0xFF1C1917),
    inverseOnSurface     = Color(0xFFFAFAF9),
    inversePrimary       = Color(0xFFFBBF24),
    scrim                = Color(0xFF000000),
)

private val DarkColors = darkColorScheme(
    primary              = Color(0xFFFBBF24),
    onPrimary            = Color(0xFF1C1917),
    primaryContainer     = Color(0xFF78350F),
    onPrimaryContainer   = Color(0xFFFEF3C7),

    secondary            = Color(0xFFA5B4FC),
    onSecondary          = Color(0xFF1E1B4B),
    secondaryContainer   = Color(0xFF3730A3),
    onSecondaryContainer = Color(0xFFE0E7FF),

    tertiary             = Color(0xFF34D399),
    onTertiary           = Color(0xFF064E3B),
    tertiaryContainer    = Color(0xFF065F46),
    onTertiaryContainer  = Color(0xFFD1FAE5),

    background           = Color(0xFF0F0D1A),
    onBackground         = Color(0xFFFAFAF9),
    surface              = Color(0xFF1A1625),
    onSurface            = Color(0xFFF5F5F4),
    surfaceVariant       = Color(0xFF27232F),
    onSurfaceVariant     = Color(0xFFA8A29E),

    outline              = Color(0xFF44403C),
    outlineVariant       = Color(0xFF292524),
    error                = Color(0xFFF87171),
    onError              = Color(0xFF7F1D1D),
    errorContainer       = Color(0xFF7F1D1D),
    onErrorContainer     = Color(0xFFFECACA),
    surfaceTint          = Color(0xFFA8A29E),
    surfaceDim           = Color(0xFF0F0D1A),
    surfaceBright        = Color(0xFF352F42),
    surfaceContainerLowest  = Color(0xFF0A0812),
    surfaceContainerLow     = Color(0xFF161221),
    surfaceContainer        = Color(0xFF1F1A2B),
    surfaceContainerHigh    = Color(0xFF272231),
    surfaceContainerHighest = Color(0xFF302A3B),
    inverseSurface       = Color(0xFFF5F5F4),
    inverseOnSurface     = Color(0xFF1C1917),
    inversePrimary       = Color(0xFFB45309),
    scrim                = Color(0xFF000000),
)

/** The palette for a mode, before any accent override. */
internal fun talonColors(darkTheme: Boolean) = if (darkTheme) DarkColors else LightColors

@Composable
fun TalonTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** When non-null, overrides `colorScheme.primary` (and its
     *  `onPrimary` contrast pair) with the user's chosen accent.
     *  null = use the brand palette unchanged.
     *
     *  Container variants (`primaryContainer` / `onPrimaryContainer`)
     *  are intentionally left as the brand to avoid a fully-recomputed
     *  tonal palette per accent — the FilterChip/IconButton surfaces
     *  that lean on `primary` get the override; chip backgrounds and
     *  larger primaryContainer fills stay brand-stable. */
    accentOverride: Color? = null,
    /** A user-made theme; it brings its own light or dark mode. */
    customTheme: CustomTheme? = null,
    content: @Composable () -> Unit,
) {
    val dark = customTheme?.dark ?: darkTheme
    val base = customTheme?.let(::customScheme) ?: talonColors(dark)
    SystemBarsAppearance(dark, base.background)
    val effective = if (accentOverride == null) base else base.copy(
        primary = accentOverride,
        onPrimary = if (accentOverride.luminance() > 0.5f) Color(0xFF1C1917)
        else Color(0xFFFFFFFF),
    )
    MaterialTheme(
        colorScheme = effective,
        typography = TalonTypography,
        content = content,
    )
}
