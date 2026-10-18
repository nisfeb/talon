package io.nisfeb.talon

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.nisfeb.talon.util.Log

/**
 * Foreground service whose only job is to keep the app process alive
 * so TlonChatRepo's SSE subscription keeps firing and new-message
 * notifications fire instantly even when the activity isn't on screen.
 * Only while no push reaches this phone for the ship (keepAliveNeeded):
 * with the ship's own %trunk or the relay pushing, it is stopped.
 *
 * The repo lives on the Application and is pinned here via the
 * process's implicit lifetime; the only work here is starting it, for a
 * start from [BootReceiver] or a sticky restart, where no screen has.
 * The reconnect on a network change and the catch-up on screen-on live
 * on the Application too, so they work with or without this service.
 * Android will still kill us under memory pressure; that's OK, the
 * repo is idempotent and the restart, or the next app open,
 * re-establishes the channel.
 *
 * Android-only: no desktop analog. A desktop Talon is one process that
 * keeps its own stream while its window is open, and closing it quits.
 */
class TalonSyncService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildOngoing(this)
        try {
            when {
                // Android 14+ (API 34+): SPECIAL_USE has no timeout. dataSync
                // is hard-capped at 6h/day on Android 15+ which kills the
                // sync mid-day with a ForegroundServiceDidNotStopInTime
                // crash — wrong fit for a chat client that can't use FCM.
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
                else -> startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: RuntimeException) {
            // A start Android does not allow from where it came (the
            // background on 12 and later, a sticky restart it refuses):
            // uncaught, that took the app down, and sticky, again.
            Log.w(TAG, "could not keep the stream in the foreground: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        live = true
        // The stream this keeps, where no screen started it. Idempotent.
        (application as? TalonApplication)?.takeIf { it.activeShipFlow.value != null }?.let { app ->
            runCatching { app.repo.start(app.session) }.onFailure { Log.w(TAG, "could not start the stream", it) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        live = false
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 100
        private const val TAG = "TalonSyncService"

        /** True while the service runs, so [refresh] has a line to redraw. */
        @Volatile
        private var live = false

        private fun buildOngoing(context: Context): Notification {
            val tapIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pending = PendingIntent.getActivity(
                context, 0, tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            // The active ship, while it refuses the saved login: "Listening
            // for new messages" was up all the while and nothing came.
            val app = context.applicationContext as? TalonApplication
            val signedOutOf = app?.activeShipFlow?.value
                ?.takeIf { runCatching { app.repo.signedOut.value }.getOrDefault(false) }
            return NotificationCompat.Builder(context, Notifications.CHANNEL_SYNC)
                .setSmallIcon(R.drawable.ic_stat_talon)
                .setContentTitle("Talon")
                // Unconditionally true: the service only pins the process —
                // the SSE channel can be down while this row is showing, so
                // it must not claim "Connected".
                .setContentText(io.nisfeb.talon.ui.screens.syncNoticeLine(signedOutOf))
                .setContentIntent(pending)
                .setOngoing(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build()
        }

        /** Redraw the line, as the ship refuses or takes the login. */
        fun refresh(context: Context) {
            if (!live) return
            runCatching {
                androidx.core.content.ContextCompat.getSystemService(context, android.app.NotificationManager::class.java)
                    ?.notify(NOTIFICATION_ID, buildOngoing(context))
            }
        }

        fun start(context: Context) {
            val intent = Intent(context, TalonSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TalonSyncService::class.java))
        }

        /**
         * After a reboot, where the screen's own check has not run: started
         * only when no push reaches this phone for the active ship, as
         * TalonApp decides it. Whether mail needs the process is not known
         * until the mail app is asked, so it does not count here.
         */
        fun startIfNeeded(app: TalonApplication) {
            if (streamNeeded(app)) runCatching { start(app) }.onFailure { Log.w(TAG, "could not start after boot: ${it.message}") }
        }

        /** No push reaches this phone for the active ship, so only a stream of its own tells it anything. */
        fun streamNeeded(app: TalonApplication): Boolean {
            val ship = app.activeShipFlow.value ?: return false
            // An endpoint with no distributor left to deliver to is none.
            val endpoint = io.nisfeb.talon.notify.TalonMessagingReceiver.cachedEndpoint(app)?.takeIf {
                runCatching { org.unifiedpush.android.connector.UnifiedPush.getDistributors(app).isNotEmpty() }.getOrDefault(true)
            }
            return io.nisfeb.talon.notify.keepAliveNeeded(
                app.relaySettings, ship, endpoint,
                shipPushBroken = ship in io.nisfeb.talon.notify.ShipPushHealth.broken.value,
            )
        }
    }
}
