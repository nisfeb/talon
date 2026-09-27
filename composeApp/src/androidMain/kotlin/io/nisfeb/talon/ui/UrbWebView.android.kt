package io.nisfeb.talon.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun UrbWebView(
    url: String,
    origin: String,
    cookie: String,
    modifier: Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            // Set the ship session cookie before the load so the very
            // first request to the authenticated lattice route carries
            // it. path=/ matches every eyre route on the ship.
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setCookie(origin, "$cookie; path=/")
            }
            WebView(ctx).apply {
                // A fixed size, the space it is given. Left to wrap its
                // content, the page's viewport had no height, so a page
                // sized to 100% of it (Lattice's reader, whose iframe fills
                // the window) fell back to an iframe's 150px: a small window
                // scrolling a page that looked cut off.
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                )
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                settings.javaScriptEnabled = true // programmable pages
                settings.domStorageEnabled = true
                webViewClient = WebViewClient() // keep navigation in-view
                tag = url
                loadUrl(url)
            }
        },
        // Only a new address loads. update runs on every recomposition, and
        // loading there started the page over each time and twice on open:
        // Lattice's reader, which now caches, syncs and refreshes itself,
        // came up slowly and only partly. The tag is the address asked for,
        // not the page's own, so following a link in it is not undone.
        update = { if (it.tag != url) { it.tag = url; it.loadUrl(url) } },
    )
}
