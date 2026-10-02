package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable

/** Nothing hovers on a touch screen: the content alone. */
@Composable
actual fun HoverTip(tip: String, content: @Composable () -> Unit) = content()
