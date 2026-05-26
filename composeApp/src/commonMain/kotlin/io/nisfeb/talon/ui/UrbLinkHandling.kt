package io.nisfeb.talon.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalUriHandler
import io.nisfeb.talon.urbit.UrbLinkLauncher

/**
 * Carries the "open this urb:// link" action down to chat renderers
 * without threading a parameter through every screen — mirrors the
 * [androidx.compose.ui.platform.LocalUriHandler] pattern. The chat
 * screens' `onLinkTap` branches on the urb:// prefix and dispatches
 * here; everything else still goes through the normal URI handler.
 *
 * Provided at the app root (App.kt on desktop, TalonApp.kt on Android)
 * wired to the platform [UrbLinkLauncher]. Default is a no-op so
 * previews / tests / surfaces that don't provide it don't crash on a tap.
 */
val LocalUrbLinkHandler = staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * Prompt shown when a tapped urb:// link has no handler on the device.
 * Links to Lattice's releases via the platform URI handler.
 */
@Composable
fun InstallLatticeDialog(onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Opens in Lattice") },
        text = {
            Text(
                "This is a urb:// link — an address on the Urbit network. " +
                    "Lattice, a peer-to-peer gemtext browser, opens these. " +
                    "Install it to follow the link.",
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                runCatching { uriHandler.openUri(UrbLinkLauncher.INSTALL_URL) }
            }) { Text("Install Lattice") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Not now") }
        },
    )
}
