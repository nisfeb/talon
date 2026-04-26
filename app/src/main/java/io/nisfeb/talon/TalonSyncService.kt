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

/**
 * Foreground service whose only job is to keep the app process alive
 * so TlonChatRepo's SSE subscription keeps firing and new-message
 * notifications fire instantly even when the activity isn't on screen.
 *
 * We don't run any work inside the service — the repo lives on the
 * Application and is pinned here via the process's implicit lifetime.
 * Android will still kill us under memory pressure; that's OK, the
 * repo is idempotent and the next app open re-establishes the channel.
 */
class TalonSyncService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildOngoing()
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
        return START_STICKY
    }

    private fun buildOngoing(): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, Notifications.CHANNEL_SYNC)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Talon")
            .setContentText("Connected")
            .setContentIntent(pending)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 100

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
    }
}
