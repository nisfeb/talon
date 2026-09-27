package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import io.ktor.client.HttpClient
import io.nisfeb.talon.urbit.LatticeInstall
import io.nisfeb.talon.urbit.UrbHttp
import io.nisfeb.talon.util.nowMs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/**
 * The whole urb:// link flow in one place, shared by both app roots:
 * check lattice is installed on the viewer's ship, offer to install it
 * (from ~ricsul-bilwyt) if not, then resolve the link — a webview
 * popover on mobile, the system browser on desktop.
 *
 * Call in a composition; it emits its own dialog/sheet overlays and
 * returns the handler to wire into [LocalUrbLinkHandler] and
 * [UrbAwareUriHandler].
 *
 * @param shipUrl the viewer ship's HTTP base, or null when signed out.
 * @param cookie  the eyre session cookie ("name=value"), or null.
 * @param poke    fire a poke at our own ship; true on success.
 */
@Composable
fun rememberUrbLinkHandler(
    http: HttpClient,
    shipUrl: () -> String?,
    cookie: () -> String?,
    poke: suspend (app: String, mark: String, body: JsonElement) -> Boolean,
): (String) -> Unit {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var viewUrl by remember { mutableStateOf<String?>(null) }
    var offerUrl by remember { mutableStateOf<String?>(null) }
    var installing by remember { mutableStateOf(false) }
    var installError by remember { mutableStateOf<String?>(null) }

    fun open(url: String) {
        val s = shipUrl() ?: return
        if (isUrbWebViewSupported) {
            viewUrl = url
        } else {
            runCatching { uriHandler.openUri(UrbHttp.readerUrl(s, url)) }
        }
    }

    // Opened at once. Asking the ship first whether lattice is there cost
    // a round trip before anything showed, on a busy ship seconds, and the
    // page answers that itself: the viewer offers the install on its 404.
    // On desktop the browser shows the error; Settings > Apps installs it.
    val handler: (String) -> Unit = { url -> open(url) }

    offerUrl?.let { pendingUrl ->
        LatticeInstallDialog(
            installing = installing,
            error = installError,
            onInstall = {
                installing = true
                installError = null
                scope.launch {
                    val s = shipUrl()
                    if (s == null) {
                        installing = false
                        installError = "Not signed in to a ship."
                        return@launch
                    }
                    // Lattice is a stock desk of the Grubbery shell: on a
                    // ship that has the shell, installing grubbery again
                    // changed nothing and this waited out the clock.
                    LatticeInstall.grubbery(http, { s }, cookie, timeoutMs = INSTALL_TIMEOUT_MS, poke = poke)().fold(
                        onSuccess = {
                            installing = false
                            offerUrl = null
                            open(pendingUrl)
                        },
                        onFailure = {
                            installing = false
                            installError = it.message
                        },
                    )
                }
            },
            onDismiss = {
                if (!installing) {
                    offerUrl = null
                    installError = null
                }
            },
        )
    }

    viewUrl?.let { u ->
        val s = shipUrl()
        val c = cookie()
        if (s != null && c != null) {
            UrbViewerSheet(
                urbUrl = u, shipUrl = s, cookie = c, onDismiss = { viewUrl = null },
                // Lattice is not on the ship: offer it, then open the page again.
                onMissing = { viewUrl = null; offerUrl = u },
            )
        }
    }

    return handler
}

private const val INSTALL_TIMEOUT_MS = 120_000L
