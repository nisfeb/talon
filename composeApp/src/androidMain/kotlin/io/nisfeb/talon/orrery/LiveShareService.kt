package io.nisfeb.talon.orrery

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.nisfeb.talon.MainActivity
import io.nisfeb.talon.Notifications
import io.nisfeb.talon.R
import io.nisfeb.talon.TalonApplication
import io.nisfeb.talon.util.Log
import io.nisfeb.talon.util.createAppHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Live location on Android (orrery 88, [LiveLocation]). While the owner
 * shares where they are with another ship, the phone sends a fix a
 * minute, as on a trip ([Trips]); Android gives a backgrounded app a fix
 * only a few times an hour, so it runs as a location foreground service
 * whose notification says so and has Stop. The ship decides when a share
 * ends; the phone keeps [sendFastUntil]'s time and reads the shares again
 * then.
 *
 * Android-only. iOS port pending: Core Location's background updates
 * with Always authorization (and Apple's location review) do the same
 * there; until then [io.nisfeb.talon.ui.isLocationSharingSupported] keeps
 * Share my location off iOS. Desktop has no analog: a computer does not
 * travel with you.
 */
object LiveShares {
    private const val PREFS = "talon_orrery_live"

    /** Until when the phone sends a fix a minute, or null. */
    fun until(ctx: Context, nowMs: Long = System.currentTimeMillis()): Long? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("until", 0L).takeIf { it > nowMs }

    /** A share runs until [untilMs], or none does: the service and the listening rate follow. */
    fun set(ctx: Context, untilMs: Long?) {
        val was = until(ctx)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("until", untilMs ?: 0L).apply()
        val now = until(ctx)
        if (now != null && LocationWatch.sharing(ctx)) {
            if (now != was) try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, LiveShareService::class.java))
            } catch (e: IllegalStateException) {
                // From the background: the next read with the app open starts it.
                Log.i(TAG, "live share not started here: ${e.message}")
            }
        } else if (was != null) ctx.stopService(Intent(ctx, LiveShareService::class.java))
        LocationWatch.resume(ctx)
    }

    /** The shares on the ship, read again: until when to keep sending, or null. */
    internal suspend fun reread(ctx: Context): Long? = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext as? TalonApplication ?: return@withContext null
        val s = app.sessionStore.active() ?: return@withContext null
        val now = System.currentTimeMillis()
        sendFastUntil(locationShares(OrreryApi(app.session.http, createAppHttpClient(), s.shipUrl).locationDoc(), now), now)
    }

    /** Stop every share on the ship: the notification's Stop. */
    internal suspend fun stopAll(ctx: Context) = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext as? TalonApplication ?: return@withContext
        val s = app.sessionStore.active() ?: return@withContext
        val api = OrreryApi(app.session.http, createAppHttpClient(), s.shipUrl)
        locationShares(api.locationDoc(), System.currentTimeMillis()).out.forEach { api.unshareLocation(it.ship) }
    }

    internal const val TAG = "LiveShares"
}

/** The share's notification and the minute-by-minute listening, until the ship says it is over. */
class LiveShareService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val due = Runnable { check() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val until = LiveShares.until(this)
        // In the foreground first, even to stop at once (see TripService).
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
        } catch (e: RuntimeException) {
            Log.w(LiveShares.TAG, "live share could not run: ${e.message}")
            end()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            // Stopped here, then on the ship; a ship that cannot be told keeps a fix that is no longer sent.
            scope.launch {
                runCatching { LiveShares.stopAll(this@LiveShareService) }.onFailure { Log.w(LiveShares.TAG, "shares not stopped on the ship: ${it.message}") }
                LiveShares.set(this@LiveShareService, null)
                end()
            }
            return START_NOT_STICKY
        }
        if (until == null) { end(); return START_NOT_STICKY }
        LocationWatch.resume(this)
        handler.removeCallbacks(due)
        handler.postDelayed(due, (until - System.currentTimeMillis()).coerceAtLeast(0))
        return START_NOT_STICKY
    }

    /** Its time came: still shared on the ship (one until home), or over. */
    private fun check() {
        scope.launch {
            val until = runCatching { LiveShares.reread(this@LiveShareService) }.getOrNull()
            LiveShares.set(this@LiveShareService, until)
            if (until == null) end()
            else handler.postDelayed(due, (until - System.currentTimeMillis()).coerceAtLeast(0))
        }
    }

    private fun end() {
        handler.removeCallbacks(due)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(due)
        scope.cancel()
        super.onDestroy()
    }

    private fun notification() = run {
        Notifications.ensureChannel(this)
        val open = PendingIntent.getActivity(
            this, NOTIFICATION_ID,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, NOTIFICATION_ID + 1, Intent(this, LiveShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        NotificationCompat.Builder(this, Notifications.CHANNEL_TRIP)
            .setSmallIcon(R.drawable.ic_stat_talon)
            .setContentTitle("Sharing where you are")
            .setContentText("Your ship sends where you are each minute until the share ends.")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val ACTION_STOP = "io.nisfeb.talon.action.LIVE_SHARE_STOP"
        private const val NOTIFICATION_ID = 7720
    }
}
