package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable

/**
 * Asking the device where it is, for the dial.
 *
 * Separate from [LocationProvider], which the `/loc` slash command
 * uses to put a pin in a message: that one wants a pair of numbers to
 * send to somebody, this one wants a place to keep, and it wants the
 * altitude too because the horizon moves with it.
 */
typealias DeviceLocation = suspend () -> Result<HomePlace>

/**
 * Null where the platform cannot find itself — which is the desktop
 * case, and the case the manual picker exists for. A refused
 * permission is not null: the call is still there and simply fails,
 * so the picker can say so and offer typing instead.
 */
@Composable
expect fun rememberDeviceLocation(): DeviceLocation?
