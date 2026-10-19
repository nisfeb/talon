package io.nisfeb.talon.ui

/** Apple Maps takes the query straight. */
actual fun mapsSearchUriFor(encodedQuery: String): String = "maps://?q=$encodedQuery"

actual fun mapsDirectionsUriFor(encodedDestination: String, mode: String?): String = appleDirectionsUri(encodedDestination, mode)
