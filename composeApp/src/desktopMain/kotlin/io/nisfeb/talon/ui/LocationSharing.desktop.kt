package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable

@Composable
actual fun rememberLocationSharing(): LocationSharing? = null

/** Nothing to stop: neither shares a location. */
actual fun stopLocationSharing() = Unit
