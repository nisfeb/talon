package io.nisfeb.talon.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import platform.UIKit.UIUserInterfaceStyle
import platform.UIKit.UIViewController

/**
 * Whether the app is drawn dark, as its theme last said; null before it
 * has. UIKit's own sheets (the photo pickers, the camera, the QR
 * scanner, alerts) follow the phone's light or dark, not Talon's: with
 * Talon set to dark, or a dark theme of the owner's own, on a phone in
 * light mode, the photo picker opened white at night.
 */
internal object IosAppearance {
    var dark: Boolean? = null
}

/**
 * This controller in the app's light or dark, set on the controller
 * itself and not on the window: a window held to one would stop
 * Compose seeing the phone's own switch while the theme follows it.
 */
internal fun UIViewController.followAppTheme() {
    overrideUserInterfaceStyle = when (IosAppearance.dark) {
        true -> UIUserInterfaceStyle.UIUserInterfaceStyleDark
        false -> UIUserInterfaceStyle.UIUserInterfaceStyleLight
        null -> UIUserInterfaceStyle.UIUserInterfaceStyleUnspecified
    }
}

@Composable
actual fun SystemBarsAppearance(darkTheme: Boolean, background: androidx.compose.ui.graphics.Color) {
    SideEffect { IosAppearance.dark = darkTheme }
}
