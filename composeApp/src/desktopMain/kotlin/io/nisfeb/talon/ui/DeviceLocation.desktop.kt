package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable

/**
 * Desktop has no location service worth the name. Rather than guess
 * from an IP address — which is somebody's ISP, not their town, and
 * which would put the dial confidently in the wrong city — the picker
 * offers typing and nothing else.
 */
@Composable
actual fun rememberDeviceLocation(): DeviceLocation? = null
