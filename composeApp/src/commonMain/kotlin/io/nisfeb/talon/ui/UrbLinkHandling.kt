package io.nisfeb.talon.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import io.nisfeb.talon.ui.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import io.nisfeb.talon.urbit.FurumLink
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
 * Open a link the way a message does: an urb:// or furum one through
 * [LocalUrbLinkHandler], anything else through the platform's handler.
 * The default for every rendered story, so a screen that shows one
 * cannot leave its links dead by not passing a handler.
 */
@Composable
fun rememberLinkOpener(): (String) -> Unit {
    val uriHandler = LocalUriHandler.current
    val urbLinkHandler = LocalUrbLinkHandler.current
    return androidx.compose.runtime.remember(uriHandler, urbLinkHandler) {
        { url ->
            if (opensOnShip(url)) urbLinkHandler(url)
            else runCatching { uriHandler.openUri(url) }
        }
    }
}

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
    /** A talon:// address: true when it was taken to its thing. */
    private val onTalon: ((String) -> Boolean)? = null,
    private val onUrb: (String) -> Unit,
) : UriHandler {
    override fun openUri(uri: String) {
        when {
            io.nisfeb.talon.urbit.TalonLink.isTalonUrl(uri) -> if (onTalon?.invoke(uri) != true) delegate.openUri(uri)
            opensOnShip(uri) -> onUrb(uri)
            else -> delegate.openUri(uri)
        }
    }
}

/**
 * Whether [url] opens through the reader's own ship: an urb:// address,
 * or a furum board or post, whose page the sharer's ship would refuse
 * the reader.
 */
fun opensOnShip(url: String): Boolean = UrbLink.isUrbUrl(url) || FurumLink.parse(url) != null

/**
 * Offered when a tapped link can't resolve because the app that reads
 * it, Lattice or furum, isn't on the user's own ship. Both come from
 * ~ricsul-bilwyt: Lattice with the Grubbery shell, furum into it.
 */
@Composable
fun ShipAppInstallDialog(
    app: String,
    /** Why the link needs it, before anything is installed. */
    pitch: String,
    installing: Boolean,
    error: String?,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
    /** Where the link has a page of its own elsewhere, opening that instead. */
    onOpenElsewhere: (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = { if (!installing) onDismiss() },
        title = { Text("Install $app?") },
        text = {
            Text(
                error
                    ?: if (installing) {
                        "Installing $app on your ship… this can take a " +
                            "moment while the software arrives over the network."
                    } else {
                        pitch
                    },
            )
        },
        confirmButton = {
            TextButton(onClick = onInstall, enabled = !installing) {
                Text(if (installing) "Installing…" else "Install")
            }
        },
        dismissButton = {
            androidx.compose.foundation.layout.Row {
                onOpenElsewhere?.let { TextButton(onClick = it, enabled = !installing) { Text("Open in browser") } }
                TextButton(onClick = onDismiss, enabled = !installing) { Text("Not now") }
            }
        },
    )
}
