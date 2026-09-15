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
