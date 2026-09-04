@file:OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class)

package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSHTTPCookieDomain
import platform.Foundation.NSHTTPCookieName
import platform.Foundation.NSHTTPCookiePath
import platform.Foundation.NSHTTPCookieValue
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration

@Composable
actual fun UrbWebView(
    url: String,
    origin: String,
    cookie: String,
    modifier: Modifier,
) {
    UIKitView(
        modifier = modifier,
        factory = {
            val config = WKWebViewConfiguration()
            val web = WKWebView(
                frame = CGRectMake(0.0, 0.0, 0.0, 0.0),
                configuration = config,
            )
            val request = NSURLRequest(uRL = NSURL(string = url))
            val host = NSURL(string = origin).host ?: ""
            val name = cookie.substringBefore('=')
            val value = cookie.substringAfter('=')
            val nsCookie = NSHTTPCookie(
                properties = mapOf<Any?, Any?>(
                    NSHTTPCookieName to name,
                    NSHTTPCookieValue to value,
                    NSHTTPCookieDomain to host,
                    NSHTTPCookiePath to "/",
                ),
            )
            // Set the session cookie, THEN load — the store write is
            // async, so loading inside its completion avoids the first
            // request racing ahead cookieless.
            if (nsCookie != null) {
                config.websiteDataStore.httpCookieStore.setCookie(nsCookie) {
                    web.loadRequest(request)
                }
            } else {
                web.loadRequest(request)
            }
            web
        },
    )
}
