package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Desktop has no embedded browser; urb:// links open the system
 *  browser (isUrbWebViewSupported = false), so this never composes. */
@Composable
actual fun UrbWebView(url: String, origin: String, cookie: String, modifier: Modifier) = Unit
