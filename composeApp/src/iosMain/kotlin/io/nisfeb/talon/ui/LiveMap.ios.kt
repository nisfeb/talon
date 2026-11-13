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

/** A named pin each, on Apple's map. Not run on a device yet. */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun PeopleMapView(pins: List<MapPin>, focus: String?, modifier: Modifier) {
    val shown = remember { mutableMapOf<String, MKPointAnnotation>() }
    val framed = remember { arrayOf<String?>(null) }
    UIKitView(
        factory = { MKMapView() },
        modifier = modifier,
        update = { map ->
            (shown.keys - pins.map { it.id }.toSet()).forEach { id -> shown.remove(id)?.let { map.removeAnnotation(it) } }
            pins.forEach { p ->
                val pin = shown.getOrPut(p.id) { MKPointAnnotation().also { map.addAnnotation(it) } }
                pin.setCoordinate(CLLocationCoordinate2DMake(p.lat, p.lon))
                pin.setTitle(p.label)
            }
            val frame = mapFrame(pins, focus)
            val key = frame?.key(pins)
            if (frame != null && framed[0] != key) {
                framed[0] = key
                val one = frame.one
                if (one != null) {
                    val span = mapSpanMetresFor(one.accuracyM)
                    map.setRegion(MKCoordinateRegionMakeWithDistance(CLLocationCoordinate2DMake(one.lat, one.lon), span, span), animated = true)
                } else map.showAnnotations(shown.values.toList(), animated = true)
            }
        },
    )
}
