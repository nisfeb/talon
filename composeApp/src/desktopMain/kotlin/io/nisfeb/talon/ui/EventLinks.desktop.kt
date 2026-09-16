package io.nisfeb.talon.ui

/** No maps app to assume on a desktop: the browser, on OpenStreetMap. */
actual fun mapsSearchUriFor(encodedQuery: String): String = "https://www.openstreetmap.org/search?query=$encodedQuery"
