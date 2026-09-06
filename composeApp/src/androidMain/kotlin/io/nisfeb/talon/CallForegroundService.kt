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
import androidx.core.app.Person
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
        val muted = intent?.getBooleanExtra(EXTRA_MUTED, false) ?: false
        val notification = buildOngoing(with, muted)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Declaring only MICROPHONE let Android 14+ kill the
                // camera the moment the app went to the background, and
                // it never came back — the tile just froze.
                //
                // The permission check is load-bearing, not caution:
                // claiming the CAMERA type without the permission throws
                // on Android 14+, and the onFailure below stops the
                // service — which would have dropped the mic hold on
                // every audio-only call.
                val camera = androidx.core.content.ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.CAMERA,
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    if (camera) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                    } else {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    },
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

    /**
     * The notification is also the call's controls outside the app —
     * the shade and the lock screen — so it carries Mute and Hang up.
     * On 12+ it is a CallStyle card, which the lock screen shows in
     * full; below that, plain actions. Both go through
     * [CallActionReceiver] to [controls], since only the running app
     * holds the media. The label reflects [muted] as the host last
     * told us; a toggle re-posts through [start] with the new truth.
     */
    private fun buildOngoing(with: String, muted: Boolean): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        fun action(name: String, code: Int) = PendingIntent.getBroadcast(
            this, code,
            Intent(this, CallActionReceiver::class.java).apply { action = name },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val toggleMute = action(CallActionReceiver.ACTION_TOGGLE_MUTE, 1)
        val hangUp = action(CallActionReceiver.ACTION_HANG_UP, 2)
        val muteLabel = if (muted) "Unmute" else "Mute"
        val muteIcon = if (muted) {
            android.R.drawable.ic_lock_silent_mode
        } else {
            android.R.drawable.ic_btn_speak_now
        }
        val title = if (with.isEmpty()) "On a party line" else "On a call with $with"
        val builder = NotificationCompat.Builder(this, Notifications.CHANNEL_CALL_ONGOING)
            .setSmallIcon(android.R.drawable.stat_sys_speakerphone)
            .setContentTitle(title)
            .setContentText(if (muted) "Muted · tap to return to Talon" else "Tap to return to Talon")
            .setContentIntent(pending)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // The whole point is reaching Mute from a locked phone.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(muteIcon, muteLabel, toggleMute)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setStyle(
                NotificationCompat.CallStyle.forOngoingCall(
                    Person.Builder().setName(with.ifEmpty { "Party line" }).setImportant(true).build(),
                    hangUp,
                ),
            )
        } else {
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Hang up", hangUp)
        }
        return builder.build()
    }

    /** What the notification's buttons do, set by the host while a
     *  call or line is live. A receiver can't reach composition
     *  state, so the host hands it these instead. */
    class Controls(val toggleMute: () -> Unit, val hangUp: () -> Unit)

    companion object {
        private const val NOTIFICATION_ID = 101
        private const val EXTRA_WITH = "with"
        private const val EXTRA_MUTED = "muted"
        private const val TAG = "CallForegroundService"

        @Volatile
        var controls: Controls? = null

        /** [with] is the peer for a 1:1 call, or empty for a line.
         *  Idempotent: calling again re-posts the notification, which
         *  is how a title or mute change reaches the shade. */
        fun start(context: Context, with: String, muted: Boolean = false) {
            val intent = Intent(context, CallForegroundService::class.java)
                .putExtra(EXTRA_WITH, with)
                .putExtra(EXTRA_MUTED, muted)
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
