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

/**
 * Directions from where the owner is to [destination], laid out and one
 * tap from starting, by [mode] where it is one of [TRAVEL_MODES].
 */
fun mapsDirectionsUri(destination: String, mode: String? = null): String =
    mapsDirectionsUriFor(destination.trim().encodeURLParameter(), travelModeOf(mode))

/** [mode] as one of [TRAVEL_MODES], whatever its case, or null for none it knows. */
fun travelModeOf(mode: String?): String? = mode?.trim()?.lowercase()?.takeIf { it in TRAVEL_MODES }

/** The platform's directions, given the destination already encoded. A computer knows no "from here": its map search. */
expect fun mapsDirectionsUriFor(encodedDestination: String, mode: String?): String

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
