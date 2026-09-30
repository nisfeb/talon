package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A platform webview that loads [url] with [cookie] scoped to
 * [origin], so authenticated lattice content on the viewer's ship
 * renders in-app. Android → WebView, iOS → WKWebView. Desktop has no
 * embedded browser and never composes this (it opens the system
 * browser instead); its actual is an empty stub.
 *
 * @param url    the lattice reader URL to load (see [io.nisfeb.talon.urbit.UrbHttp]).
 * @param origin the ship base URL the [cookie] belongs to.
     * @param cookie the viewer's eyre session cookie, in "name=value" form.
 * @param onMissing the page itself came back 404: eyre has no lattice to
 *   route it to (lattice answers a page it lacks with 200 and its own page).
 */
@Composable
expect fun UrbWebView(
    url: String,
    origin: String,
    cookie: String,
    modifier: Modifier,
    onMissing: () -> Unit = {},
)
