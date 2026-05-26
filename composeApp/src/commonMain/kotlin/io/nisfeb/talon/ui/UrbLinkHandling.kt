package io.nisfeb.talon.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import io.nisfeb.talon.urbit.UrbLink
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
 * A [UriHandler] that routes `urb://` links to [onUrb] (the Lattice
 * handoff) and delegates everything else to [delegate].
 *
 * Provided as the app-root [LocalUriHandler] so that link paths which
 * open via Compose's built-in handling — `LinkAnnotation.Url` clicks
 * in statuses and bios (see `linkifyStatus`), not just the chat
 * screens' explicit `onLinkTap` — also hand urb:// to Lattice. Without
 * this, those links go straight to the platform URI handler: on
 * desktop that's `xdg-open`, which opens the system browser when no
 * urb:// scheme handler is registered; on Android the OS happens to
 * route urb:// to Lattice, which is why this only bit desktop.
 */
class UrbAwareUriHandler(
    private val delegate: UriHandler,
    private val onUrb: (String) -> Unit,
) : UriHandler {
    override fun openUri(uri: String) {
        if (UrbLink.isUrbUrl(uri)) onUrb(uri) else delegate.openUri(uri)
    }
}

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
