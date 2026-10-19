package io.nisfeb.talon.ui

/** No maps app to assume on a desktop: the browser, on OpenStreetMap. */
actual fun mapsSearchUriFor(encodedQuery: String): String = "https://www.openstreetmap.org/search?query=$encodedQuery"

actual fun mapsDirectionsFor(encodedDestination: String, mode: String?, point: Pair<Double, Double>?): MapsLink =
    MapsLink(mapsSearchUriFor(encodedDestination), route = false)

actual fun directionsWantPoint(): Boolean = false
