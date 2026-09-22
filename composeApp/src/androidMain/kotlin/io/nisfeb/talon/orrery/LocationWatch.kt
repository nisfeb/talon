package io.nisfeb.talon.orrery

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.nisfeb.talon.TalonApplication
import io.nisfeb.talon.ui.hasLocationPermission
import io.nisfeb.talon.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The phone tells orrery where the owner is when they move.
 *
 * Android-only for now. iOS port pending: CLLocationManager's
 * significant-change monitoring with Always authorization does the same
 * there. Desktop has no analog: a computer does not move with you.
 *
 * LocationManager is asked for a fix on a move of [MIN_MOVE_M], at most
 * every [MIN_GAP_MS], delivered by PendingIntent to [LocationReceiver],
 * which the platform wakes with the app closed; no timer runs. The
 * platform's own LocationManager, not Play services, which GrapheneOS
 * does not have unless the owner installed them. A registration does
 * not outlive a reboot or an update, so [resume] runs on every start of
 * the process, which a boot, a background job or opening the app all
 * cause.
 */
object LocationWatch {
    private const val PREFS = "talon_orrery_location"
    private const val KEY_ON = "on"
    private const val KEY_PAUSED = "paused"
    private const val WORK = "talon-orrery-location"
    const val MIN_MOVE_M = 400f
    const val MIN_GAP_MS = 10 * 60 * 1000L

    private var flow: MutableStateFlow<Boolean>? = null

    /**
     * The application context, kept from the first call. Stopping has to
     * be reachable from the pipe, which has no context of its own, and
     * the application's outlives every screen.
     */
    private var app: Context? = null

    @Synchronized
    fun on(ctx: Context): StateFlow<Boolean> = flow ?: MutableStateFlow(isOn(ctx)).also {
        app = ctx.applicationContext
        flow = it
    }

    /** Off, from wherever: the pipe going off takes this with it. */
    fun stop() = app?.let { set(it, false) } ?: Unit

    /**
     * Listening held, or taken up again, with the switch left as saved.
     * Saved too: [resume] runs on every start of the process, a boot or
     * a background job included, and listened again for a ship with no
     * pipe until the app was next opened.
     */
    fun pause(paused: Boolean) {
        val ctx = app ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PAUSED, paused).apply()
        if (paused) stopListening(ctx) else resume(ctx)
    }

    private fun isOn(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)

    /** Location, and on Android 10 and later location all the time, which hearing a move with the app closed needs. */
    fun allowed(ctx: Context): Boolean = hasLocationPermission(ctx) &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION))

    fun granted(ctx: Context, permission: String) = ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

    /** Turn it on or off, and listen or stop listening to match. */
    fun set(ctx: Context, on: Boolean) {
        app = ctx.applicationContext
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
        (on(ctx) as MutableStateFlow).value = on
        if (on) resume(ctx) else stopListening(ctx)
    }

    /** Listen, where the switch is on and the permission holds. */
    fun resume(ctx: Context) {
        app = ctx.applicationContext
        if (!isOn(ctx) || !allowed(ctx)) return
        if (ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PAUSED, false)) return
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val providers = lm.getProviders(true)
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { it in providers } ?: return Log.i(TAG, "no location provider is on")
        try {
            @Suppress("MissingPermission")
            lm.requestLocationUpdates(provider, MIN_GAP_MS, MIN_MOVE_M, intent(ctx))
        } catch (e: SecurityException) {
            Log.w(TAG, "location refused: ${e.message}")
        }
    }

    private fun stopListening(ctx: Context) {
        (ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.removeUpdates(intent(ctx))
    }

    private fun intent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 0, Intent(ctx, LocationReceiver::class.java),
        // Mutable: the platform adds the fix to it.
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0),
    )

    /** Hand a fix to the worker; a newer one replaces one still waiting. */
    fun send(ctx: Context, loc: Location) {
        val data = workDataOf(
            "lat" to loc.latitude, "lon" to loc.longitude,
            "acc" to (if (loc.hasAccuracy()) loc.accuracy.toDouble() else MAX_MATCH_M), "at" to loc.time,
        )
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            WORK, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<LocationWorker>().setInputData(data)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }

    private const val TAG = "LocationWatch"
}

/** Woken by the platform with a fix; the work is the worker's. */
class LocationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        @Suppress("DEPRECATION")
        val loc = intent.getParcelableExtra<Location>(LocationManager.KEY_LOCATION_CHANGED)
            ?: intent.getParcelableArrayListExtra<Location>(LocationManager.KEY_LOCATIONS)?.maxByOrNull { it.time }
            ?: return
        LocationWatch.send(context, loc)
    }
}

/** One fix: a place name where the phone can look one up, then orrery. */
class LocationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? TalonApplication ?: return Result.success()
        val session = app.sessionStore.active() ?: return Result.success()
        val d = inputData
        val fix = LocationFix(d.getDouble("lat", 0.0), d.getDouble("lon", 0.0), d.getDouble("acc", MAX_MATCH_M), d.getLong("at", System.currentTimeMillis()))
        return sendLocation(app.session.http, app.db, session.shipUrl, session.ship, fix, placeName(fix)).fold(
            onSuccess = { Result.success() },
            onFailure = {
                Log.w("LocationWorker", "location not sent: ${it.message}")
                if (runAttemptCount < 3) Result.retry() else Result.success()
            },
        )
    }

    /** The neighbourhood or town, and the region: short, and no nearer than that. */
    private fun placeName(fix: LocationFix): String? {
        if (!Geocoder.isPresent()) return null
        @Suppress("DEPRECATION")
        val a = runCatching { Geocoder(applicationContext).getFromLocation(fix.lat, fix.lon, 1)?.firstOrNull() }.getOrNull() ?: return null
        return listOfNotNull(a.subLocality ?: a.locality ?: a.subAdminArea, a.adminArea).distinct().joinToString(", ").ifBlank { null }
    }
}
