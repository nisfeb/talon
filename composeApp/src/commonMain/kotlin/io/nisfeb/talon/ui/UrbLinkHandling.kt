package io.nisfeb.talon.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalUriHandler
import io.nisfeb.talon.urbit.UrbLaunchResult
import io.nisfeb.talon.urbit.UrbLinkLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Carries the "open this urb:// link" action down to chat renderers
 * without threading a parameter through every screen — mirrors the
 * [androidx.compose.ui.platform.LocalUriHandler] pattern. The chat
 * screens' `onLinkTap` branches on the urb:// prefix and dispatches
 * here; everything else still goes through the normal URI handler.
 *
 * Default is a no-op so previews / tests that don't wrap content in
 * [ProvideUrbLinkHandler] don't crash on a tap.
 */
val LocalUrbLinkHandler = staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * Wraps [content] with a urb:// handler backed by [launcher], and
 * hosts the "install Lattice" prompt that appears when no urb://
 * handler is present on the device.
 *
 * The launch runs off the main thread — the desktop launcher shells
 * out to `xdg-mime` / `xdg-open`, which would otherwise block the UI
 * thread on the process round-trip.
 */
@Composable
fun ProvideUrbLinkHandler(
    launcher: UrbLinkLauncher,
    content: @Composable () -> Unit,
) {
    var promptUrl by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val handler: (String) -> Unit = remember(launcher) {
        { url ->
            scope.launch {
                val result = withContext(Dispatchers.IO) { launcher.open(url) }
                if (result != UrbLaunchResult.Opened) promptUrl = url
            }
        }
    }
    CompositionLocalProvider(LocalUrbLinkHandler provides handler) {
        content()
    }
    promptUrl?.let { url ->
        InstallLatticeDialog(onDismiss = { promptUrl = null })
    }
}

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
