package io.nisfeb.talon

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Thin wrapper around NotificationManager for new-message alerts.
 *
 * We key notifications on `whom` so multiple messages from the same
 * conversation collapse into one row rather than stacking up. Tapping
 * a notification opens MainActivity with the `whom` as an extra so
 * TalonApp can route straight into that conversation.
 */
object Notifications {

    const val CHANNEL_MESSAGES = "messages"
    const val CHANNEL_SYNC = "sync"
    const val EXTRA_OPEN_WHOM = "open_whom"
    const val EXTRA_SCROLL_TO_MESSAGE = "scroll_to_message"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_MESSAGES) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_MESSAGES,
                    "Messages",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "New chat and channel messages"
                    enableLights(true)
                    enableVibration(true)
                }
            )
        }
        if (mgr.getNotificationChannel(CHANNEL_SYNC) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_SYNC,
                    "Background sync",
                    NotificationManager.IMPORTANCE_MIN,
                ).apply {
                    description = "Keeps Talon connected so new messages arrive instantly"
                    setShowBadge(false)
                }
            )
        }
    }

    fun showMessage(
        context: Context,
        whom: String,
        postId: String?,
        title: String,
        body: String,
        sentMs: Long,
    ) {
        val mgr = ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?: return

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_WHOM, whom)
            if (postId != null) putExtra(EXTRA_SCROLL_TO_MESSAGE, postId)
        }
        val pending = PendingIntent.getActivity(
            context,
            whom.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setWhen(sentMs)
            .setShowWhen(true)
            .build()

        // Tag = whom so new messages from the same conversation replace
        // the previous notification rather than stacking.
        mgr.notify(whom, NOTIFICATION_ID, notification)
    }

    fun clear(context: Context, whom: String) {
        val mgr = ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?: return
        mgr.cancel(whom, NOTIFICATION_ID)
    }

    private const val NOTIFICATION_ID = 1001
}
