package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.nisfeb.talon.urbit.FurumLink
import io.nisfeb.talon.urbit.LatticeInstall
import io.nisfeb.talon.urbit.UrbHttp
import io.nisfeb.talon.util.nowMs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/**
 * The whole flow for a link that opens on the viewer's own ship, shared
 * by both app roots: an urb:// address in lattice's reader, or a furum
 * board or post in furum. It opens at once, a webview popover on mobile
 * and the system browser on desktop, and where the ship answers 404 it
 * offers the app (from ~ricsul-bilwyt).
 *
 * Call it where [LocalUriHandler] is still the platform's, before the
 * app root provides [UrbAwareUriHandler]: a furum page's own URL is a
 * furum link, and handed back to that one it would come straight here.
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

    fun pageOf(url: String, s: String): String = FurumLink.parse(url)?.pageUrl(s) ?: UrbHttp.readerUrl(s, url)

    fun open(url: String) {
        val s = shipUrl() ?: return
        if (isUrbWebViewSupported) {
            viewUrl = url
        } else {
            runCatching { uriHandler.openUri(pageOf(url, s)) }
        }
    }

    // Opened at once. Asking the ship first whether lattice is there cost
    // a round trip before anything showed, on a busy ship seconds, and the
    // page answers that itself: the viewer offers the install on its 404.
    // On desktop the browser shows the error; Settings > Apps installs it.
    val handler: (String) -> Unit = { url -> open(url) }

    offerUrl?.let { pendingUrl ->
        val furum = FurumLink.parse(pendingUrl)
        ShipAppInstallDialog(
            app = if (furum != null) "furum" else "Lattice",
            pitch = if (furum != null) {
                "${furum.shorthand} is on furum, the forums of the Urbit network. Your ship reads it with furum, " +
                    "which isn't installed yet. Add it to your ship from ~ricsul-bilwyt?"
            } else {
                "This is a urb:// link — an address on the Urbit network. Opening it needs Lattice, which isn't " +
                    "installed on your ship yet. Install it from ~ricsul-bilwyt?"
            },
            // A furum link someone shared has its own page on their ship,
            // which may be public.
            onOpenElsewhere = pendingUrl.takeIf { furum != null && it.startsWith("http") }?.let { u ->
                { offerUrl = null; runCatching { uriHandler.openUri(u) } }
            },
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
                    // Furum is a desk added into the shell.
                    val install = if (furum != null) {
                        LatticeInstall.shellDesk(
                            http, { s }, name = "furum", cookie = cookie(), timeoutMs = INSTALL_TIMEOUT_MS, poke = poke,
                            answers = { url -> furumAnswers(http, url, cookie()) },
                        )
                    } else {
                        LatticeInstall.grubbery(http, { s }, cookie, timeoutMs = INSTALL_TIMEOUT_MS, poke = poke)
                    }
                    install().fold(
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
                address = FurumLink.parse(u)?.shorthand ?: u,
                pageUrl = pageOf(u, s),
                shipUrl = s, cookie = c, onDismiss = { viewUrl = null },
                // The app is not on the ship: offer it, then open the page again.
                onMissing = { viewUrl = null; offerUrl = u },
            )
        }
    }

    return handler
}

private const val INSTALL_TIMEOUT_MS = 120_000L

/** Whether furum answers the owner on [shipUrl]: its about page, which any furum serves. */
private suspend fun furumAnswers(http: HttpClient, shipUrl: String, cookie: String?): Boolean =
    io.nisfeb.talon.util.runSuspendCatching {
        http.get("${shipUrl.trimEnd('/')}/apps/furum/about") { cookie?.let { header(HttpHeaders.Cookie, it) } }.status.isSuccess()
    }.getOrDefault(false)
