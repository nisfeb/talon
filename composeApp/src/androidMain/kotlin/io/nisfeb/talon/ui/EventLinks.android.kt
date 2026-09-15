package io.nisfeb.talon.ui

/** geo: with a query lands in whichever maps app the user has. */
actual fun mapsSearchUriFor(encodedQuery: String): String = "geo:0,0?q=$encodedQuery"
