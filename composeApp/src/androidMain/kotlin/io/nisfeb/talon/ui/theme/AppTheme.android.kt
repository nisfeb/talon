package io.nisfeb.talon.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import io.nisfeb.talon.TalonApplication
import io.nisfeb.talon.ui.AccentMode
import io.nisfeb.talon.ui.AccentSettings
import io.nisfeb.talon.ui.parseHexColor

/**
 * [TalonTheme] as the owner set it up: light or dark as chosen, the
 * accent (brand, custom or the profile's colour), a theme of their own
 * and the font. The app and the dictation card both draw in it. The card
 * drew in the brand palette, whatever theme the app was in.
 *
 * [paintWindow] false leaves the window alone: the card sits over
 * whatever was on screen, and painting its window would hide that.
 */
@Composable
fun TalonAppTheme(app: TalonApplication, paintWindow: Boolean = true, content: @Composable () -> Unit) {
    val themeMode by app.themePreference.mode.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemePreference.Mode.System -> systemDark
        ThemePreference.Mode.Light -> false
        ThemePreference.Mode.Dark -> true
    }
    // Observe the ship via `app.activeShipFlow` and re-key the contacts
    // stream on it. Reading `app.session.shipName` directly does not
    // recompose on a ship switch (a lateinit var reassigned in
    // buildShipScoped), and caching the flow with `remember(app)` pinned
    // the old per-ship database: "accent stays as the previous ship's
    // color."
    val accentSettings by app.uiSettings.accentSettings.collectAsState()
    val activeShipState by app.activeShipFlow.collectAsState()
    val allShipsState by app.allShipsFlow.collectAsState()
    val multiShip = allShipsState.size >= 2
    val ownContacts by remember(activeShipState) {
        app.db.contacts().stream()
    }.collectAsState(initial = emptyList())
    val profileAccent = remember(ownContacts, activeShipState) {
        val ship = activeShipState ?: return@remember null
        ownContacts.firstOrNull { it.ship == ship }?.color
            ?.let(::parseHexColor)
    }
    val accentEnabled = AccentSettings.isEnabled(accentSettings, multiShip)
    val accentOverride = remember(accentEnabled, accentSettings, profileAccent) {
        if (!accentEnabled) null
        else when (accentSettings.mode) {
            AccentMode.Brand -> null
            AccentMode.Custom -> accentSettings.customHex?.let(::parseHexColor)
            AccentMode.Profile -> profileAccent
        }
    }
    val themeSettings by app.uiSettings.themeSettings.collectAsState()
    TalonTheme(
        darkTheme = darkTheme, accentOverride = accentOverride, customTheme = themeSettings.active,
        fontFamily = io.nisfeb.talon.ui.rememberAppFontFamily(app.uiSettings),
        paintWindow = paintWindow,
        content = content,
    )
}
