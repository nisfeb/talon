package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Never shown: isLiveMapSupported is false here, and Open map goes to the browser. */
@Composable
actual fun LiveMapView(lat: Double, lon: Double, accuracyM: Double?, modifier: Modifier) = Unit
