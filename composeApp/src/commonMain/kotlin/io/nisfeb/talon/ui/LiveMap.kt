package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A map centred on a live share's latest fix, with a dot there, moved
 * as each new fix arrives. Shown only where [isLiveMapSupported].
 */
@Composable
expect fun LiveMapView(lat: Double, lon: Double, accuracyM: Double?, modifier: Modifier = Modifier)

/** How close to look: street level for an exact fix, the neighbourhood for an area share (about a kilometre). */
fun mapZoomFor(accuracyM: Double?): Double = when {
    accuracyM == null || accuracyM <= 100.0 -> 16.0
    accuracyM < 1000.0 -> 14.0
    else -> 13.0
}

/** The same, as the width of ground to show, for MapKit's regions. */
fun mapSpanMetresFor(accuracyM: Double?): Double = when (mapZoomFor(accuracyM)) {
    16.0 -> 600.0
    14.0 -> 2_500.0
    else -> 5_000.0
}
