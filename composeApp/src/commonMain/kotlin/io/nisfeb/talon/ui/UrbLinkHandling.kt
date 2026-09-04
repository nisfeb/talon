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
 * Offered when a tapped urb:// link can't resolve because lattice
 * (the %grubbery desk) isn't installed on the user's own ship.
 * Installing pulls %grubbery from ~ricsul-bilwyt via kiln.
 */
@Composable
fun LatticeInstallDialog(
    installing: Boolean,
    error: String?,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!installing) onDismiss() },
        title = { Text("Install Lattice?") },
        text = {
            Text(
                error
                    ?: if (installing) {
                        "Installing Lattice on your ship… this can take a " +
                            "moment while the software arrives over the network."
                    } else {
                        "This is a urb:// link — an address on the Urbit " +
                            "network. Opening it needs Lattice, which isn't " +
                            "installed on your ship yet. Install it from " +
                            "~ricsul-bilwyt?"
                    },
            )
        },
        confirmButton = {
            TextButton(onClick = onInstall, enabled = !installing) {
                Text(if (installing) "Installing…" else "Install")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !installing) { Text("Not now") }
        },
    )
}
