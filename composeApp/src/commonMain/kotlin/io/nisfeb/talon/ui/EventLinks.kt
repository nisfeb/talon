package io.nisfeb.talon.ui

import io.ktor.http.encodeURLParameter

/**
 * What in an event's place and note can be acted on: a phone number
 * is dialled or copied, an address is looked up on a map or copied.
 */

private val PHONE = Regex("""(?<![\w.])\+?\(?\d[\d\s().-]{5,}\d(?!\w)""")
private val DATE_LIKE = Regex("""^\d{4}-\d{1,2}-\d{1,2}$|^\d{1,2}[./-]\d{1,2}[./-]\d{2,4}$""")

/** Phone numbers in [text], as written: seven to fifteen digits, with
 *  the spaces, dots, dashes and brackets people put in them; a date
 *  is not one. */
fun phoneNumbersIn(text: String): List<String> =
    PHONE.findAll(text).map { it.value.trim().trimEnd('.') }
        .filter { p -> p.count { it.isDigit() } in 7..15 && !DATE_LIKE.matches(p) }
        .distinct().toList()

private val URL = Regex("""(?i)(?<![\w@])(?:https?://|urb://|talon://|www\.)[^\s<>()"']+""")
private val IMAGE_EXT = Regex("""(?i)\.(png|jpe?g|gif|webp|avif|bmp)(?:[?#].*)?$""")

/** Web links in [text], trailing punctuation dropped. */
fun urlsIn(text: String): List<String> =
    URL.findAll(text).map { it.value.trimEnd('.', ',', ';', ':', '!', '?') }.filter { it.length > 8 }.distinct().toList()

/** The links in [text] that name an image file, ready to load. */
fun imageUrlsIn(text: String): List<String> =
    urlsIn(text).filter { !it.startsWith("urb://", ignoreCase = true) && !it.startsWith("talon://", ignoreCase = true) && IMAGE_EXT.containsMatchIn(it) }.map { openableUrl(it) }

/** [url] as something a browser opens: a bare www. gets its scheme. */
fun openableUrl(url: String): String = if (url.startsWith("www.", ignoreCase = true)) "https://$url" else url

/** A tel: link for [phone], digits and a leading plus only. */
fun telUri(phone: String): String = "tel:" + phone.filter { it.isDigit() || it == '+' }

/** Where a search for [query] opens on this platform's map. */
fun mapsSearchUri(query: String): String = mapsSearchUriFor(query.trim().encodeURLParameter())

/** The platform's map search, given the query already encoded. */
expect fun mapsSearchUriFor(encodedQuery: String): String

/** How a route is travelled, as the maps hand-off takes it. */
val TRAVEL_MODES: Set<String> = setOf("walking", "driving", "bicycling", "transit")

/** A maps hand-off: its link, and whether it lays out a route or only finds the place. */
data class MapsLink(val uri: String, val route: Boolean)

/**
 * Directions from where the owner is to [destination], laid out and one
 * tap from starting, by [mode] where it is one of [TRAVEL_MODES]. [point]
 * is where it is, for a maps app that routes only to coordinates
 * ([directionsWantPoint]).
 */
fun mapsDirections(destination: String, mode: String? = null, point: Pair<Double, Double>? = null): MapsLink =
    mapsDirectionsFor(destination.trim().encodeURLParameter(), travelModeOf(mode), point)

/** [mode] as one of [TRAVEL_MODES], whatever its case, or null for none it knows. */
fun travelModeOf(mode: String?): String? = mode?.trim()?.lowercase()?.takeIf { it in TRAVEL_MODES }

/** The platform's directions, given the destination already encoded. A computer knows no "from here": its map search. */
expect fun mapsDirectionsFor(encodedDestination: String, mode: String?, point: Pair<Double, Double>?): MapsLink

/** Whether this phone's maps app routes only to coordinates, so the place is looked up first: OsmAnd, with no Google Maps. */
expect fun directionsWantPoint(): Boolean

/**
 * Android's directions: Google Maps' link where it is installed; else
 * OsmAnd's own navigation to [point] where OsmAnd is and the place was
 * found; else a geo: search, which any maps app answers.
 */
fun androidDirections(encodedDestination: String, mode: String?, point: Pair<Double, Double>?, googleMaps: Boolean, osmand: Boolean): MapsLink = when {
    googleMaps -> MapsLink(googleDirectionsUri(encodedDestination, mode), route = true)
    osmand && point != null -> MapsLink(osmandNavigateUri(encodedDestination, mode, point), route = true)
    else -> MapsLink("geo:0,0?q=$encodedDestination", route = false)
}

/**
 * OsmAnd's navigate command (its ExternalApiHelper): to coordinates,
 * which is all its navigation takes, by the profile for [mode], else its
 * own default, the car. Open to any app: no permission is asked.
 */
fun osmandNavigateUri(encodedName: String, mode: String?, point: Pair<Double, Double>): String =
    "osmand.api://navigate?dest_lat=${point.first}&dest_lon=${point.second}&dest_name=$encodedName" + when (mode) {
        "walking" -> "&profile=pedestrian"
        "driving" -> "&profile=car"
        "bicycling" -> "&profile=bicycle"
        "transit" -> "&profile=public_transport"
        else -> ""
    }

/**
 * Google Maps' directions link, for Android: the app where it is
 * installed, else Google Maps in the browser.
 * ponytail: another maps app is not offered it; geo: carries no route.
 */
fun googleDirectionsUri(encodedDestination: String, mode: String?): String =
    "https://www.google.com/maps/dir/?api=1&destination=$encodedDestination" + (mode?.let { "&travelmode=$it" } ?: "")

/** Apple Maps' directions from here, for iOS. Its dirflg has driving, walking and transit, and nothing for a bicycle. */
fun appleDirectionsUri(encodedDestination: String, mode: String?): String =
    "maps://?daddr=$encodedDestination" + when (mode) {
        "driving" -> "&dirflg=d"
        "walking" -> "&dirflg=w"
        "transit" -> "&dirflg=r"
        else -> ""
    }
