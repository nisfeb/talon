package io.nisfeb.talon.util

/**
 * Decode the handful of HTML entities that show up in the text we scrape
 * (OG meta tags in `LinkPreviewCache`, page bodies in `UrlFetcher`). Not a
 * general entity table — a real one would mean a parser dependency for the
 * six escapes anything actually emits.
 *
 * `&amp;` is decoded LAST: doing it first turns the double-escaped
 * `&amp;lt;` into a literal `<`.
 */
internal fun decodeHtmlEntities(s: String): String = s
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&#x27;", "'")
    .replace("&apos;", "'")
    .replace("&nbsp;", " ")
    .replace("&amp;", "&")
