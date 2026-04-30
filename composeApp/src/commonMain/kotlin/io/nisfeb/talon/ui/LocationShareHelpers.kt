// app/src/main/java/io/nisfeb/talon/ui/LocationShare.kt. The Android-only
// sensor fetch helpers (`hasLocationPermission`, `fetchLastKnownLocation`,
// `requestSingleUpdate`) stay in app/ since they touch LocationManager.
// commonMain only needs the URL formatter and the tag codec to render
// `[loc|…]` widgets in StoryRenderer.
package io.nisfeb.talon.ui

fun osmViewerUrl(lat: Double, lng: Double, zoom: Int = 15): String {
    val la = "%.5f".format(lat)
    val ln = "%.5f".format(lng)
    return "https://www.openstreetmap.org/?mlat=$la&mlon=$ln#map=$zoom/$la/$ln"
}

fun encodeLocTag(lat: Double, lng: Double): String {
    val la = "%.5f".format(lat)
    val ln = "%.5f".format(lng)
    return "[loc|$la|$ln]"
}

val LOC_TAG_RE: Regex = Regex("\\[loc\\|(-?\\d+(?:\\.\\d+)?)\\|(-?\\d+(?:\\.\\d+)?)\\]")

data class DecodedLoc(val lat: Double, val lng: Double)

fun decodeLocTag(text: String): DecodedLoc? {
    val m = LOC_TAG_RE.find(text) ?: return null
    val lat = m.groupValues[1].toDoubleOrNull() ?: return null
    val lng = m.groupValues[2].toDoubleOrNull() ?: return null
    if (lat.isNaN() || lng.isNaN() || lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
    return DecodedLoc(lat, lng)
}

fun formatLocationShare(lat: Double, lng: Double): String {
    val header = "📍 Location"
    val link = osmViewerUrl(lat, lng)
    val tag = encodeLocTag(lat, lng)
    return "$header\n$link\n$tag"
}
