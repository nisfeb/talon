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
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                settings.javaScriptEnabled = true // programmable pages
                settings.domStorageEnabled = true
                webViewClient = WebViewClient() // keep navigation in-view
                loadUrl(url)
            }
        },
        update = { it.loadUrl(url) },
    )
}
