package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Point
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection

/**
 * MapLibre over OpenFreeMap's tiles: free, no key, and allowed in an app
 * (OpenStreetMap's own tile servers are not). The tiles for the area on
 * screen come from OpenFreeMap. A dot marks the fix and moves with it.
 */
@Composable
actual fun LiveMapView(lat: Double, lon: Double, accuracyM: Double?, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = remember {
        MapLibre.getInstance(context)
        MapView(context).also { mv ->
            mv.onCreate(null)
            mv.getMapAsync { map ->
                map.cameraPosition = CameraPosition.Builder().target(LatLng(lat, lon)).zoom(mapZoomFor(accuracyM)).build()
                map.setStyle(Style.Builder().fromUri(STYLE)) { style ->
                    style.addSource(GeoJsonSource(SOURCE, Point.fromLngLat(lon, lat)))
                    style.addLayer(
                        CircleLayer(SOURCE, SOURCE).withProperties(
                            PropertyFactory.circleRadius(8f),
                            PropertyFactory.circleColor("#2962FF"),
                            PropertyFactory.circleStrokeWidth(2f),
                            PropertyFactory.circleStrokeColor("#FFFFFF"),
                        ),
                    )
                }
            }
        }
    }
    DisposableEffect(lifecycle, view) {
        val watch = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(watch)
        view.onStart()
        view.onResume()
        onDispose {
            lifecycle.removeObserver(watch)
            view.onPause()
            view.onStop()
            view.onDestroy()
        }
    }
    AndroidView(factory = { view }, modifier = modifier, update = { mv ->
        mv.getMapAsync { map ->
            map.style?.getSourceAs<GeoJsonSource>(SOURCE)?.setGeoJson(Point.fromLngLat(lon, lat))
            map.animateCamera(CameraUpdateFactory.newLatLng(LatLng(lat, lon)))
        }
    })
}

private const val STYLE = "https://tiles.openfreemap.org/styles/liberty"
private const val SOURCE = "who"

private fun features(pins: List<MapPin>): FeatureCollection = FeatureCollection.fromFeatures(
    pins.map { p -> Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).also { it.addStringProperty("label", p.label) } },
)

/** A dot and a name each, on the same tiles as [LiveMapView]. Not run on a device yet. */
@Composable
actual fun PeopleMapView(pins: List<MapPin>, focus: String?, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val framed = remember { arrayOf<String?>(null) }
    val view = remember {
        MapLibre.getInstance(context)
        MapView(context).also { mv ->
            mv.onCreate(null)
            mv.getMapAsync { map ->
                map.setStyle(Style.Builder().fromUri(STYLE)) { style ->
                    style.addSource(GeoJsonSource(PEOPLE, features(pins)))
                    style.addLayer(
                        CircleLayer(PEOPLE, PEOPLE).withProperties(
                            PropertyFactory.circleRadius(8f),
                            PropertyFactory.circleColor("#2962FF"),
                            PropertyFactory.circleStrokeWidth(2f),
                            PropertyFactory.circleStrokeColor("#FFFFFF"),
                        ),
                    )
                    style.addLayer(
                        SymbolLayer("$PEOPLE-names", PEOPLE).withProperties(
                            PropertyFactory.textField(Expression.get("label")),
                            // The style's own font: the default stack is not among OpenFreeMap's glyphs.
                            PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
                            PropertyFactory.textSize(13f),
                            PropertyFactory.textOffset(arrayOf(0f, 1.4f)),
                            PropertyFactory.textHaloColor("#FFFFFF"),
                            PropertyFactory.textHaloWidth(1.5f),
                            PropertyFactory.textAllowOverlap(true),
                        ),
                    )
                }
            }
        }
    }
    DisposableEffect(lifecycle, view) {
        val watch = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(watch)
        view.onStart()
        view.onResume()
        onDispose {
            lifecycle.removeObserver(watch)
            view.onPause()
            view.onStop()
            view.onDestroy()
        }
    }
    AndroidView(factory = { view }, modifier = modifier, update = { mv ->
        mv.getMapAsync { map ->
            map.style?.getSourceAs<GeoJsonSource>(PEOPLE)?.setGeoJson(features(pins))
            val frame = mapFrame(pins, focus) ?: return@getMapAsync
            val key = frame.key(pins)
            if (framed[0] == key) return@getMapAsync
            framed[0] = key
            val one = frame.one
            if (one != null) map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(one.lat, one.lon), mapZoomFor(one.accuracyM)))
            else map.animateCamera(
                CameraUpdateFactory.newLatLngBounds(
                    LatLngBounds.Builder().include(LatLng(frame.south, frame.west)).include(LatLng(frame.north, frame.east)).build(), 160,
                ),
            )
        }
    })
}

private const val PEOPLE = "people"
