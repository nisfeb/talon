package io.nisfeb.talon.orrery

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
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
import io.nisfeb.talon.util.Log

/**
 * Trip mode on Android ([Trip]). Android gives a backgrounded app a fix
 * only a few times an hour, so a trip runs as a location foreground
 * service with an "On the way" notification. A push cannot start one
 * from the background (Android 12 on); an exact alarm can, so the trip
 * is armed at the plan's alert time whenever the plan is read, and a tap
 * on the leave alert starts it too.
 *
 * Android-only. iOS port pending: Core Location's background updates
 * with Always authorization do the same there. Desktop has no analog: a
 * computer does not travel with you.
 */
object Trips {
    private const val PREFS = "talon_orrery_trip"
    private const val ACTION_ALARM = "io.nisfeb.talon.action.TRIP"
    private const val REQ = 7703

    /** Start [plan]'s trip at its alert time, where location sharing is on. */
    fun arm(ctx: Context, plan: LeavePlan, nowMs: Long = System.currentTimeMillis()) {
        val trip = tripOf(plan) ?: return
        if (nowMs >= trip.untilMs || !LocationWatch.sharing(ctx)) return
        save(ctx, trip, running = active(ctx, nowMs)?.key == trip.key)
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val at = maxOf(trip.fromMs, nowMs)
        // Exact, because only an exact alarm may start the service from
        // the background; one that cannot be exact starts it on the tap.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm(ctx))
        }
    }

    /** No plan ahead: the trip not yet started is not started. One under way runs on. */
    fun disarm(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java)?.cancel(alarm(ctx))
        if (active(ctx) == null) ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** The trip under way, if one is. */
    fun active(ctx: Context, nowMs: Long = System.currentTimeMillis()): Trip? =
        stored(ctx)?.takeIf { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("running", false) && nowMs < it.untilMs }

    /** Begin the armed trip now: from its alarm, or the owner tapping the alert. */
    fun start(ctx: Context, nowMs: Long = System.currentTimeMillis()) {
        val trip = stored(ctx)?.takeIf { it.live(nowMs) } ?: return
        if (!LocationWatch.sharing(ctx)) return
        try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, TripService::class.java))
        } catch (e: IllegalStateException) {
            // Not allowed from where it was asked (a background start): the alarm or the tap will.
            Log.i(TAG, "trip not started here: ${e.message}")
            return
        }
        save(ctx, trip, running = true)
    }

    internal fun stored(ctx: Context): Trip? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Trip(
            key = p.getString("key", null) ?: return null,
            name = p.getString("name", null).orEmpty(),
            fromMs = p.getLong("from", 0), untilMs = p.getLong("until", 0),
        )
    }

    private fun save(ctx: Context, trip: Trip, running: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("key", trip.key).putString("name", trip.name)
            .putLong("from", trip.fromMs).putLong("until", trip.untilMs).putBoolean("running", running)
            .apply()
    }

    /** Over: listening goes back to every ten minutes. */
    internal fun end(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("running", false).apply()
        LocationWatch.resume(ctx)
    }

    private fun alarm(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, REQ, Intent(ctx, TripAlarmReceiver::class.java).setAction(ACTION_ALARM),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    internal const val TAG = "Trips"
}

/** The plan's alert time: the trip begins. */
class TripAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Trips.start(context)
}

/** The trip itself: the notification, the minute-by-minute listening, and its end. */
class TripService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val finish = Runnable { stop() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stop(); return START_NOT_STICKY }
        val trip = Trips.stored(this)?.takeIf { it.live(System.currentTimeMillis()) }
        // In the foreground first, even to stop at once: a service started
        // with startForegroundService that stops without it takes the app
        // down ("did not then call Service.startForeground()"), and a
        // disarm can clear the trip between the start and here.
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(trip ?: Trip("", "", 0, 0)),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
        } catch (e: RuntimeException) {
            // Location taken away, or a start Android does not allow from here.
            Log.w(Trips.TAG, "trip could not run: ${e.message}")
            stop()
            return START_NOT_STICKY
        }
        if (trip == null) { stop(); return START_NOT_STICKY }
        LocationWatch.resume(this)
        handler.removeCallbacks(finish)
        handler.postDelayed(finish, (trip.untilMs - System.currentTimeMillis()).coerceAtLeast(0))
        return START_NOT_STICKY
    }

    private fun stop() {
        handler.removeCallbacks(finish)
        Trips.end(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(finish)
        // Ended any other way (the system, a crash): listening still goes back.
        if (Trips.active(this) != null) Trips.end(this)
        super.onDestroy()
    }

    private fun notification(trip: Trip) = run {
        Notifications.ensureChannel(this)
        val open = PendingIntent.getActivity(
            this, NOTIFICATION_ID,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(Notifications.EXTRA_OPEN_ORRERY, trip.key.substringBefore('@'))
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, NOTIFICATION_ID + 1, Intent(this, TripService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        NotificationCompat.Builder(this, Notifications.CHANNEL_TRIP)
            .setSmallIcon(R.drawable.ic_stat_talon)
            .setContentTitle(if (trip.name.isBlank()) "On the way" else "On the way to ${trip.name}")
            .setContentText("Your ship hears where you are each minute until you get there.")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .build()
    }

    companion object {
        private const val ACTION_STOP = "io.nisfeb.talon.action.TRIP_STOP"
        private const val NOTIFICATION_ID = 7704
    }
}
