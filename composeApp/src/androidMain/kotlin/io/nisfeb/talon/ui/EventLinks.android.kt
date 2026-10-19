package io.nisfeb.talon.ui

/** geo: with a query lands in whichever maps app the user has. */
actual fun mapsSearchUriFor(encodedQuery: String): String = "geo:0,0?q=$encodedQuery"

actual fun mapsDirectionsFor(encodedDestination: String, mode: String?, point: Pair<Double, Double>?): MapsLink =
    androidDirections(encodedDestination, mode, point, MapsApps.googleMaps(), MapsApps.osmand())

actual fun directionsWantPoint(): Boolean = !MapsApps.googleMaps() && MapsApps.osmand()

/**
 * Which maps apps this phone has, for the directions hand-off: Android
 * only, a package check that the other platforms have no use for. Set by
 * TalonApplication; unset, neither is found and geo: answers.
 */
internal object MapsApps {
    @Volatile var context: android.content.Context? = null

    private fun has(vararg packages: String): Boolean {
        val pm = context?.packageManager ?: return false
        return packages.any { p -> runCatching { pm.getApplicationInfo(p, 0).enabled }.getOrDefault(false) }
    }

    fun googleMaps(): Boolean = has("com.google.android.apps.maps")
    fun osmand(): Boolean = has("net.osmand.plus", "net.osmand")
}
