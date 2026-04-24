package io.nisfeb.talon.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * `/loc` helpers: encode a structured tag a decoder can render as a
 * live map, plus the human-readable OSM link for plain clients.
 * Mirrors yap/ui/src/util/location.ts for the serialization side;
 * adds Android-specific geolocation fetch for the command handler.
 */

fun osmViewerUrl(lat: Double, lng: Double, zoom: Int = 15): String {
    val la = "%.5f".format(lat)
    val ln = "%.5f".format(lng)
    return "https://www.openstreetmap.org/?mlat=$la&mlon=$ln#map=$zoom/$la/$ln"
}

/** Machine-readable tag. One-shot — no expiry baked in. */
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

/**
 * Whether we currently hold either fine or coarse location permission.
 * Callers should prompt via ActivityResultContracts if this is false.
 */
fun hasLocationPermission(ctx: Context): Boolean {
    val fine = ContextCompat.checkSelfPermission(
        ctx, Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(
        ctx, Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    return fine || coarse
}

/**
 * Best-effort one-shot location fetch. Prefers the most recent fix
 * across providers we're allowed to query, avoiding any new sensor
 * wake since /loc callers typically want an instant answer.
 *
 * Requires ACCESS_COARSE_LOCATION. Returns null if no provider has a
 * cached fix or we lack permission.
 */
suspend fun fetchLastKnownLocation(ctx: Context): Location? {
    if (!hasLocationPermission(ctx)) return null
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    return try {
        val providers = lm.getProviders(true)
        var best: Location? = null
        for (p in providers) {
            @Suppress("MissingPermission")
            val loc = lm.getLastKnownLocation(p) ?: continue
            if (best == null || loc.time > best.time) best = loc
        }
        best ?: requestSingleUpdate(lm)
    } catch (_: SecurityException) {
        null
    }
}

/**
 * Fall back when no cached fix exists. Requests a single update with
 * a 10s timeout; returns null on timeout or provider error.
 */
private suspend fun requestSingleUpdate(lm: LocationManager): Location? =
    suspendCancellableCoroutine { cont ->
        val providers = lm.getProviders(true)
        if (providers.isEmpty()) {
            cont.resume(null); return@suspendCancellableCoroutine
        }
        val handlerThread = android.os.HandlerThread("talon-loc").apply { start() }
        val looper = handlerThread.looper
        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(loc: Location) {
                cont.resume(loc)
                lm.removeUpdates(this)
                handlerThread.quitSafely()
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {
                cont.resume(null)
                lm.removeUpdates(this)
                handlerThread.quitSafely()
            }
        }
        val provider = when {
            LocationManager.FUSED_PROVIDER in providers -> LocationManager.FUSED_PROVIDER
            LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
            LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
            else -> providers.first()
        }
        try {
            // The listener self-removes in onLocationChanged, so this
            // behaves as a single-shot update on every supported API.
            @Suppress("MissingPermission")
            lm.requestLocationUpdates(provider, 0L, 0f, listener, looper)
        } catch (e: SecurityException) {
            cont.resumeWithException(e)
        }
        cont.invokeOnCancellation {
            lm.removeUpdates(listener)
            handlerThread.quitSafely()
        }
    }

fun formatLocationShare(lat: Double, lng: Double): String {
    val header = "📍 Location"
    val link = osmViewerUrl(lat, lng)
    val tag = encodeLocTag(lat, lng)
    return "$header\n$link\n$tag"
}
