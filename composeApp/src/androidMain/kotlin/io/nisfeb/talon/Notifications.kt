package io.nisfeb.talon

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import io.nisfeb.talon.util.Log

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
    const val CHANNEL_LOOPS = "loops"
    // v2: the Ringer owns sound and vibration now, so the channel must
    // do neither. A channel's alerting cannot be changed after it is
    // created, so changing it means a new id.
    const val CHANNEL_CALLS = "calls_v2"

    /**
     * The "on a call" notification a live call's foreground service
     * posts, separate from [CHANNEL_CALLS] because importance is a
     * channel property. Incoming calls are HIGH so they take over the
     * screen; an ongoing call must not, or connecting a call pops a
     * heads-up banner over the call UI you are already looking at.
     */
    const val CHANNEL_CALL_ONGOING = "call_ongoing"

    const val EXTRA_ANSWER_FROM = "answer_from"
    const val EXTRA_ANSWER_CALL_ID = "answer_call_id"
    /** A ship to call back, from a missed-call notification. */
    const val EXTRA_CALL_BACK = "call_back"

    /** One notification id for calls: only one can ring at a time. */
    private const val CALL_NOTIFICATION_ID = 0x0CA11
    private const val MISSED_CALL_NOTIFICATION_ID = 0x0CA12
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
        if (mgr.getNotificationChannel(CHANNEL_CALLS) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_CALLS,
                    "Calls",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Incoming calls"
                    // Silent by design. A channel plays its sound and
                    // its vibration pattern exactly once — which is the
                    // bug this replaces: the ringtone blipped and the
                    // pattern buzzed twice, and there is no flag to
                    // make either repeat. Ringer loops both for the
                    // life of the ring and stops with the call.
                    setSound(null, null)
                    enableLights(true)
                    enableVibration(false)
                }
            )
        }
        if (mgr.getNotificationChannel(CHANNEL_CALL_ONGOING) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_CALL_ONGOING,
                    "Ongoing calls",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shown while a call or party line is live"
                    setShowBadge(false)
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
        if (mgr.getNotificationChannel(CHANNEL_LOOPS) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_LOOPS,
                    "Loops",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Results from your scheduled loops"
                    enableLights(true)
                }
            )
        }
    }

    /**
     * Someone is ringing this ship.
     *
     * A full-screen intent so it takes over a locked screen the way a
     * phone call does, and CallStyle where the platform has it (31+)
     * so it renders as a call rather than a chat notification.
     *
     * Answering opens the app carrying EXTRA_ANSWER_FROM, and TalonApp
     * accepts as soon as the controller is up and ringing for that
     * peer — the media negotiation lives in CallController and needs a
     * running app and a live channel, so it can't happen here. It has
     * to be handled on arrival, though: without that the action just
     * showed the in-app ring and the user had to press Answer twice.
     * Declining works from here alone, being one poke and no media.
     */
    fun showIncomingCall(context: Context, from: String, callId: String) {
        ensureChannel(context)
        val mgr = ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?: return

        // A foregrounded app is already ringing this call itself —
        // CallController loops its own incoming tone under the in-app
        // answer UI — so ringing here too plays both at once. Post
        // silently instead of skipping: if the user backgrounds
        // mid-ring, answer and decline are still one notification away.
        // Visibility alone isn't enough: on the login screen there is
        // no controller and nothing else will ring, so the check also
        // requires one to be alive. ponytail: a live controller whose
        // SSE channel is mid-backoff still reads as "will ring" — the
        // user gets a visible-but-silent notification in that window;
        // publish real ring state from the controller if it ever bites.
        // inAppRinging is the real thing rather than a proxy for it: a
        // home-pressed app still has a live controller and still rings
        // through the SSE path, but is not STARTED — so both ringers
        // sounded for up to 45s. Visibility stays as the optimistic
        // case (we are about to ring), because silence is the worse
        // failure the STARTED clause was avoiding.
        val inAppRingExpected = callControllerLive &&
            (
                inAppRinging ||
                    ProcessLifecycleOwner.get()
                        .lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                )

        val answerIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_ANSWER_FROM, from)
            putExtra(EXTRA_ANSWER_CALL_ID, callId)
        }
        val answer = PendingIntent.getActivity(
            context, callId.hashCode(), answerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val decline = PendingIntent.getBroadcast(
            context, callId.hashCode() + 1,
            Intent(context, CallActionReceiver::class.java).apply {
                action = CallActionReceiver.ACTION_DECLINE
                putExtra(EXTRA_ANSWER_FROM, from)
                putExtra(EXTRA_ANSWER_CALL_ID, callId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(from)
            .setContentText("Incoming call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            // Without this a missed call is permanent: the notification
            // is ongoing (so it can't be swiped away) and the only
            // things that cancel it are the Decline action and a
            // running app. A caller who gives up while the phone sleeps
            // would leave "Incoming call" on screen forever.
            .setTimeoutAfter(io.nisfeb.talon.call.CallController.DEFAULT_RING_TIMEOUT_MS)
            .setContentIntent(answer)

        // The full-screen intent stays on in BOTH branches: Android 12+
        // refuses a CallStyle notification without one — build() throws
        // IllegalArgumentException — and the system only launches an
        // FSI over a locked or dark screen, never over the app the
        // user is currently looking at.
        builder.setFullScreenIntent(answer, true)
        if (inAppRingExpected) builder.setSilent(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    Person.Builder().setName(from).setImportant(true).build(),
                    decline,
                    answer,
                ),
            )
        } else {
            builder
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Decline", decline)
                .addAction(android.R.drawable.sym_action_call, "Answer", answer)
        }
        mgr.notify(CALL_NOTIFICATION_ID, builder.build())
        shownCallId = callId
        shownCallFrom = from
        when {
            inAppRingExpected -> Unit // the in-app ring is already sounding
            // Notifications denied means notify() above was dropped on
            // the floor — 45 seconds of ringing with no surface to
            // answer or decline from is worse than staying quiet.
            !NotificationManagerCompat.from(context).areNotificationsEnabled() ->
                Log.i(TAG, "notifications disabled; skipping the ring for call $callId")
            // Same hole one level down: blocking just the calls
            // channel (long-press the notification → turn off) drops
            // notify() while areNotificationsEnabled stays true.
            mgr.getNotificationChannel(CHANNEL_CALLS)?.importance ==
                NotificationManager.IMPORTANCE_NONE ->
                Log.i(TAG, "calls channel blocked; skipping the ring for call $callId")
            else -> io.nisfeb.talon.notify.Ringer.start(
                context,
                io.nisfeb.talon.call.CallController.DEFAULT_RING_TIMEOUT_MS,
            )
        }
    }

    /** The call the current ring surface belongs to. Lets a late
     *  ring-cancel push for an earlier call leave a newer ring alone —
     *  there is only one call notification id, so without this the
     *  cancel for caller A would silence caller B's ring. */
    @Volatile
    private var shownCallId: String? = null
    /** Who rang, for the missed-call notice a cancel may become. */
    @Volatile
    private var shownCallFrom: String? = null

    /** True while TalonApp has a live CallController composed — the
     *  thing that actually plays the in-app ring. Set by TalonApp; the
     *  push path reads it to decide whether ringing here would double
     *  up or be the only ring this call gets. */
    @Volatile
    var callControllerLive: Boolean = false

    /** True while the in-app ringer is actually sounding. Set by
     *  AndroidCallSoundPlayer, not inferred from window state. */
    @Volatile
    var inAppRinging: Boolean = false

    /** Stop ringing — answered, declined, or the caller gave up. */
    /**
     * A ring that ended unanswered on our side. A self-managed telecom
     * call never reaches the system call log (that needs the dialer
     * role), so this is where a missed call lives on Android: a notice
     * with Call back and Message, which is how every non-dialer app
     * behaves. Silent: the ring already made its noise.
     */
    fun showMissedCall(context: Context, from: String, name: String) {
        ensureChannel(context)
        val mgr = ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?: return
        val callBack = PendingIntent.getActivity(
            context, from.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_CALL_BACK, from)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val message = PendingIntent.getActivity(
            context, from.hashCode() + 1,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_WHOM, from)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(android.R.drawable.sym_call_missed)
            .setContentTitle("Missed call")
            .setContentText(name)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(callBack)
            .addAction(android.R.drawable.sym_action_call, "Call back", callBack)
            .addAction(android.R.drawable.sym_action_chat, "Message", message)
            .build()
        mgr.notify(MISSED_CALL_NOTIFICATION_ID, n)
    }

    fun cancelIncomingCall(context: Context) {
        // Stop the noise first: the notification going away while the
        // phone keeps buzzing is worse than either alone.
        io.nisfeb.talon.notify.Ringer.stop()
        shownCallId = null
        ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?.cancel(CALL_NOTIFICATION_ID)
    }

    /** Cancel only if the ring surface still belongs to [callId] —
     *  the relay's ring-cancel push races the next incoming ring. */
    fun cancelIncomingCall(context: Context, callId: String) {
        if (shownCallId == callId) cancelIncomingCall(context)
    }

    /**
     * The relay's ring-cancel. A ring still showing when it lands is
     * a missed call — unless [reason] says another of the user's
     * devices answered, or the app is alive, in which case the call
     * controller sees the end itself and TalonApp posts the notice.
     * This is the dead-process path: pushes woke us, nothing else.
     */
    fun ringCancelled(context: Context, callId: String, reason: String?) {
        if (shownCallId != callId) return
        val from = shownCallFrom
        cancelIncomingCall(context)
        if (reason == "answered" || callControllerLive || from == null) return
        showMissedCall(context, from, from)
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

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_WHOM, whom)
            if (parentId != null) {
                putExtra(EXTRA_OPEN_THREAD, parentId)
                if (postId != null) putExtra(EXTRA_THREAD_ANCHOR, postId)
            } else if (postId != null) {
                putExtra(EXTRA_SCROLL_TO_MESSAGE, postId)
            }
        }
        val pending = PendingIntent.getActivity(
            context,
            whom.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_talon)
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

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_WHOM, whom)
            if (parentId != null) {
                putExtra(EXTRA_OPEN_THREAD, parentId)
                if (postId != null) putExtra(EXTRA_THREAD_ANCHOR, postId)
            } else if (postId != null) {
                putExtra(EXTRA_SCROLL_TO_MESSAGE, postId)
            }
        }
        val pending = PendingIntent.getActivity(
            context,
            ("watchword:$whom").hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = "${terms.joinToString(", ")} in $label"

        val notification = NotificationCompat.Builder(context, CHANNEL_WATCHWORDS)
            .setSmallIcon(R.drawable.ic_stat_talon)
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

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_DIGEST, ship)
            putExtra(EXTRA_DIGEST_DATE, dateLocal)
        }
        val pending = PendingIntent.getActivity(
            context,
            ("digest:$ship:$dateLocal").hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_DAILY_DIGEST)
            .setSmallIcon(R.drawable.ic_stat_talon)
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

    /**
     * Loop-result notification. One channel for all loops; tag =
     * "loop:<loopId>" (the stable row id, NOT the display name) so
     * re-running a loop replaces its own row while distinct loops keep
     * separate rows even if they share a name (Android dedupes per
     * (tag, id)). Tap just opens the app — there's no per-loop deep link yet.
     */
    fun showLoop(context: Context, loopId: Long, title: String, body: String, whenMs: Long) {
        val mgr = ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?: return

        val tag = "loop:$loopId"
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            tag.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_LOOPS)
            .setSmallIcon(R.drawable.ic_stat_talon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setWhen(whenMs)
            .setShowWhen(true)
            .build()

        mgr.notify(tag, NOTIFICATION_ID, notification)
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
    private const val TAG = "Notifications"
}
