package io.nisfeb.talon

// TEMPORARY DUPLICATE of app/src/main/java/io/nisfeb/talon/Notifications.kt — remove on Stage B3

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
    const val CHANNEL_WATCHWORDS = "watchwords"
    const val CHANNEL_DAILY_DIGEST = "daily-digest"
    const val EXTRA_OPEN_WHOM = "open_whom"
    const val EXTRA_SCROLL_TO_MESSAGE = "scroll_to_message"
    /** When the notification is for a reply, the parent post id —
     *  TalonApp uses it to route into ThreadScreen rather than the
     *  main chat list. */
    const val EXTRA_OPEN_THREAD = "open_thread"
    /** When EXTRA_OPEN_THREAD is set, the specific reply id to anchor
     *  the thread's initial scroll on. */
    const val EXTRA_THREAD_ANCHOR = "thread_anchor"
    const val EXTRA_OPEN_DIGEST = "open_digest"
    const val EXTRA_DIGEST_DATE = "digest_date"

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
        if (mgr.getNotificationChannel(CHANNEL_WATCHWORDS) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_WATCHWORDS,
                    "Watchwords",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Hits on user-defined watchword terms"
                    enableLights(true)
                    enableVibration(true)
                }
            )
        }
        if (mgr.getNotificationChannel(CHANNEL_DAILY_DIGEST) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_DAILY_DIGEST,
                    "Daily digest",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Morning brief — fires once a day"
                    enableLights(true)
                }
            )
        }
    }

    fun showMessage(
        context: Context,
        whom: String,
        postId: String?,
        /** Non-null when this notification is for a reply — the
         *  parent's id. Tap routes into ThreadScreen anchored on
         *  [postId] (the reply itself). */
        parentId: String? = null,
        title: String,
        body: String,
        sentMs: Long,
    ) {
        val mgr = ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?: return

        // TODO(Stage D): Deep-linking from notifications is broken in composeApp v1 — the
        // production MainActivity is at io.nisfeb.talon.MainActivity (app/ module) which isn't
        // reachable from composeApp. Stage D's MainActivity port resolves this. Notification
        // still fires; tap just doesn't route anywhere meaningful until then.
        val activityClass = runCatching {
            Class.forName("io.nisfeb.talon.MainActivity")
        }.getOrNull()
        val pending = if (activityClass != null) {
            val tapIntent = Intent(context, activityClass).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_WHOM, whom)
                if (parentId != null) {
                    putExtra(EXTRA_OPEN_THREAD, parentId)
                    if (postId != null) putExtra(EXTRA_THREAD_ANCHOR, postId)
                } else if (postId != null) {
                    putExtra(EXTRA_SCROLL_TO_MESSAGE, postId)
                }
            }
            PendingIntent.getActivity(
                context,
                whom.hashCode(),
                tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else null

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

    /**
     * Watchword-hit notification. Same tap intent shape as [showMessage]
     * but on a separate channel and tag namespace so it can be tuned
     * independently and never collides with regular chat notifications.
     */
    fun showWatchwordHit(
        context: Context,
        whom: String,
        postId: String?,
        parentId: String? = null,
        terms: List<String>,
        label: String,
        body: String,
        sentMs: Long,
    ) {
        val mgr = ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?: return

        // TODO(Stage D): Deep-linking from notifications is broken in composeApp v1 — see
        // showMessage for full explanation.
        val activityClass = runCatching {
            Class.forName("io.nisfeb.talon.MainActivity")
        }.getOrNull()
        val pending = if (activityClass != null) {
            val tapIntent = Intent(context, activityClass).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_WHOM, whom)
                if (parentId != null) {
                    putExtra(EXTRA_OPEN_THREAD, parentId)
                    if (postId != null) putExtra(EXTRA_THREAD_ANCHOR, postId)
                } else if (postId != null) {
                    putExtra(EXTRA_SCROLL_TO_MESSAGE, postId)
                }
            }
            PendingIntent.getActivity(
                context,
                ("watchword:$whom").hashCode(),
                tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else null

        val title = "${terms.joinToString(", ")} in $label"

        val notification = NotificationCompat.Builder(context, CHANNEL_WATCHWORDS)
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

        // Tag = "watchword:<whom>" so repeated hits in the same chat
        // collapse into one row, but never collide with showMessage's
        // <whom>-tagged notification for the same chat.
        mgr.notify("watchword:$whom", NOTIFICATION_ID, notification)
    }

    /**
     * Daily digest notification. Tap routes into MainActivity with
     * EXTRA_OPEN_DIGEST set; TalonApp picks it up and navigates to
     * DailyDigestScreen for [ship] / [dateLocal].
     *
     * Tag = "digest:<ship>:<dateLocal>" so re-firing the same day
     * replaces. The notification ID is shared with the chat-message
     * notifications because Android dedupes per (tag, id).
     */
    fun showDailyDigest(
        context: Context,
        ship: String,
        dateLocal: String,
        title: String,
        body: String,
        generatedAtMs: Long,
    ) {
        val mgr = ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?: return

        // TODO(Stage D): Deep-linking from notifications is broken in composeApp v1 — see
        // showMessage for full explanation.
        val activityClass = runCatching {
            Class.forName("io.nisfeb.talon.MainActivity")
        }.getOrNull()
        val pending = if (activityClass != null) {
            val tapIntent = Intent(context, activityClass).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_DIGEST, ship)
                putExtra(EXTRA_DIGEST_DATE, dateLocal)
            }
            PendingIntent.getActivity(
                context,
                ("digest:$ship:$dateLocal").hashCode(),
                tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else null

        val notification = NotificationCompat.Builder(context, CHANNEL_DAILY_DIGEST)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setWhen(generatedAtMs)
            .setShowWhen(true)
            .build()

        mgr.notify("digest:$ship:$dateLocal", NOTIFICATION_ID, notification)
    }

    fun clear(context: Context, whom: String) {
        val mgr = ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?: return
        mgr.cancel(whom, NOTIFICATION_ID)
    }

    /** Cancel all notifications associated with a chat — called when the
     *  user opens the conversation. Also cancels the watchword tag for the
     *  same chat so both notification rows disappear together. */
    fun cancelAllForChat(context: Context, whom: String) {
        val mgr = ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?: return
        mgr.cancel(whom, NOTIFICATION_ID)
        mgr.cancel("watchword:$whom", NOTIFICATION_ID)
    }

    private const val NOTIFICATION_ID = 1001
}
