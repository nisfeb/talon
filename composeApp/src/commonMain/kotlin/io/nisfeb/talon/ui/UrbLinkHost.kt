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
    // Once we've seen lattice installed this session, stop re-probing
    // on every tap.
    var known by remember { mutableStateOf(false) }

    fun open(url: String) {
        val s = shipUrl() ?: return
        if (isUrbWebViewSupported) {
            viewUrl = url
        } else {
            runCatching { uriHandler.openUri(UrbHttp.readerUrl(s, url)) }
        }
    }

    val handler: (String) -> Unit = { url ->
        val s = shipUrl()
        if (s != null) {
            if (known) {
                open(url)
            } else {
                scope.launch {
                    if (LatticeInstall.isInstalled(http, s)) {
                        known = true
                        open(url)
                    } else {
                        offerUrl = url
                    }
                }
            }
        }
    }

    offerUrl?.let { pendingUrl ->
        LatticeInstallDialog(
            installing = installing,
            error = installError,
            onInstall = {
                installing = true
                installError = null
                scope.launch {
                    val s = shipUrl()
                    val (app, mark, body) = LatticeInstall.installPoke()
                    val poked = s != null && poke(app, mark, body)
                    if (!poked) {
                        installing = false
                        installError = "Your ship refused the install."
                        return@launch
                    }
                    // The desk arrives over the network after kiln
                    // accepts the poke; poll the manifest until it does.
                    val deadline = nowMs() + INSTALL_TIMEOUT_MS
                    while (nowMs() < deadline) {
                        delay(3_000)
                        if (s != null && LatticeInstall.isInstalled(http, s)) {
                            known = true
                            installing = false
                            offerUrl = null
                            open(pendingUrl)
                            return@launch
                        }
                    }
                    installing = false
                    installError =
                        "Install is taking a while — it may still finish. " +
                        "Try the link again shortly."
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
            UrbViewerSheet(urbUrl = u, shipUrl = s, cookie = c, onDismiss = { viewUrl = null })
        }
    }

    return handler
}

private const val INSTALL_TIMEOUT_MS = 120_000L
