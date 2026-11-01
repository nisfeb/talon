package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreLocation.CLLocationCoordinate2DMake
import platform.MapKit.MKCoordinateRegionMakeWithDistance
import platform.MapKit.MKMapView
import platform.MapKit.MKPointAnnotation

/** MapKit: Apple's own map, no key and nothing added to the app. The pin moves with each fix. */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun LiveMapView(lat: Double, lon: Double, accuracyM: Double?, modifier: Modifier) {
    val pin = remember { MKPointAnnotation() }
    UIKitView(
        factory = { MKMapView().apply { addAnnotation(pin) } },
        modifier = modifier,
        update = { map ->
            val at = CLLocationCoordinate2DMake(lat, lon)
            pin.setCoordinate(at)
            val span = mapSpanMetresFor(accuracyM)
            map.setRegion(MKCoordinateRegionMakeWithDistance(at, span, span), animated = true)
        },
    )
}
