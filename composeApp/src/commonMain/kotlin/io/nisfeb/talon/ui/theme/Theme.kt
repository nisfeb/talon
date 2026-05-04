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
)

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
    content: @Composable () -> Unit,
) {
    val base = if (darkTheme) DarkColors else LightColors
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
