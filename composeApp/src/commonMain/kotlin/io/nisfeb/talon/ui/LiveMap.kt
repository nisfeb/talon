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

/** One person on [PeopleMapView]: where they are, under the name they go by. */
data class MapPin(val id: String, val label: String, val lat: Double, val lon: Double, val accuracyM: Double?)

/**
 * Everyone sharing where they are, on one map, a named pin each. It
 * frames them all, or the one [focus] names. Moved again only when who
 * is shown changes, so a new fix does not pull the map from where the
 * reader dragged it. Shown only where [isLiveMapSupported].
 */
@Composable
expect fun PeopleMapView(pins: List<MapPin>, focus: String?, modifier: Modifier = Modifier)

/** What [PeopleMapView] looks at: one pin close up, or the box around them all. */
data class MapFrame(val south: Double, val west: Double, val north: Double, val east: Double, val one: MapPin?) {
    /** Changes when the map should move: who is framed, not where they are now. */
    fun key(pins: List<MapPin>): String = one?.id ?: pins.map { it.id }.sorted().joinToString(",")
}

fun mapFrame(pins: List<MapPin>, focus: String?): MapFrame? {
    if (pins.isEmpty()) return null
    val one = pins.firstOrNull { it.id == focus } ?: pins.singleOrNull()
    val of = one?.let(::listOf) ?: pins
    return MapFrame(of.minOf { it.lat }, of.minOf { it.lon }, of.maxOf { it.lat }, of.maxOf { it.lon }, one)
}
