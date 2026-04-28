package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable

/**
 * Compose-bound location provider for `/loc`. Returns null on
 * platforms without a sensor stack (desktop today). On Android the
 * provider wraps the permission launcher: on first call without
 * permission it kicks off a permission request and returns a failure
 * with a "try again" message; subsequent calls succeed once the user
 * has granted access.
 */
@Composable
expect fun rememberLocationProvider(): LocationProvider?
