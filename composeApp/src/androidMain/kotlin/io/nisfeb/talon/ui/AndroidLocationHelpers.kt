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
 * Android-only sensor helpers for `/loc`. The pure helpers
 * (osmViewerUrl, encodeLocTag, decodeLocTag, formatLocationShare)
 * live in commonMain LocationShareHelpers.kt; only the Context-bound
 * permission check + LocationManager fetch land here.
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
        // A device with no fix to give never calls back; iOS's version
        // times out inside itself, and this one now does too rather
        // than leaving the picker's button spinning for good.
        best ?: kotlinx.coroutines.withTimeoutOrNull(FIX_TIMEOUT_MS) { requestSingleUpdate(lm) }
    } catch (_: SecurityException) {
        null
    }
}

private const val FIX_TIMEOUT_MS = 20_000L

/**
 * Where the phone is now, dated now: the last fix when it is fresh, else
 * a new one, else the last one again. The phone is listening for moves,
 * so a last fix it has not replaced is still where it is, and dated as
 * it was taken the ship read it as old and sent a share nothing.
 */
suspend fun fetchCurrentLocation(ctx: Context): Location? {
    val last = fetchLastKnownLocation(ctx) ?: return null
    val now = System.currentTimeMillis()
    if (freshFix(last.time, now)) return last
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    val fresh = try {
        lm?.let { kotlinx.coroutines.withTimeoutOrNull(FIX_TIMEOUT_MS) { requestSingleUpdate(it) } }
    } catch (_: SecurityException) {
        null
    }
    return fresh ?: Location(last).apply { time = System.currentTimeMillis() }
}

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
