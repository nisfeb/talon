package io.nisfeb.talon.ui

/** Apple Maps takes the query straight. */
actual fun mapsSearchUriFor(encodedQuery: String): String = "maps://?q=$encodedQuery"

actual fun mapsDirectionsFor(encodedDestination: String, mode: String?, point: Pair<Double, Double>?): MapsLink =
    MapsLink(appleDirectionsUri(encodedDestination, mode), route = true)

actual fun directionsWantPoint(): Boolean = false
