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
 * Holds the microphone for the duration of a call.
 *
 * Android-only: no desktop analog — desktop has no background
 * microphone restriction.
 *
 * From Android 14, an app that isn't visible gets *silence* from the
 * microphone unless a foreground service of type `microphone` is
 * running. Playback is unrestricted, which is why the symptom was so
 * confusing: you could still hear the other person perfectly, they
 * just couldn't hear you the moment the screen went off.
 *
 * TalonSyncService can't cover this. Its type is specialUse (chosen
 * so the SSE channel isn't subject to dataSync's 6h/day cap), it is
 * started from BootReceiver while the app is in the background where
 * a microphone-typed start would be rejected, and the user can turn
 * it off. A call needs the mic whether or not background sync is on,
 * so it gets its own service with its own lifetime: started when
 * media goes live, stopped when the call ends.
 */
class CallForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val with = intent?.getStringExtra(EXTRA_WITH).orEmpty()
        val notification = buildOngoing(with)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Both types. Declaring only MICROPHONE meant the camera
                // was killed the moment the app went to the background
                // on Android 14+, and it never came back for the rest of
                // the call — the tile just froze.
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure {
            // Denied (permission revoked mid-call, or a background
            // start Android refused). The call still works while the
            // app is on screen, so this degrades rather than crashes.
            Log.w(TAG, "could not hold the mic in the background", it)
            stopSelf()
        }
        // Not sticky: a restarted service would hold the mic for a call
        // that is long over.
        return START_NOT_STICKY
    }

    private fun buildOngoing(with: String): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, Notifications.CHANNEL_CALL_ONGOING)
            .setSmallIcon(android.R.drawable.stat_sys_speakerphone)
            .setContentTitle(if (with.isEmpty()) "On a party line" else "On a call with $with")
            .setContentText("Tap to return to Talon")
            .setContentIntent(pending)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 101
        private const val EXTRA_WITH = "with"
        private const val TAG = "CallForegroundService"

        /** [with] is the peer for a 1:1 call, or empty for a line. */
        fun start(context: Context, with: String) {
            val intent = Intent(context, CallForegroundService::class.java)
                .putExtra(EXTRA_WITH, with)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Log.w(TAG, "could not start the call service", it) }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, CallForegroundService::class.java))
            }
        }
    }
}
