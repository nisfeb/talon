package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable

// Desktop has no portable device-location surface (IP geolocation
// would require a network call to a third party). Stage F can swap
// in a CLLocation/Geoclue actual; today /loc surfaces a clean error.
@Composable
actual fun rememberLocationProvider(): LocationProvider? = null
