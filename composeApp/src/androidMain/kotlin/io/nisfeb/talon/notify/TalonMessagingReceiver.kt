package io.nisfeb.talon.notify

import android.content.Context
import android.content.SharedPreferences
import io.nisfeb.talon.ui.contactMapNow
import io.nisfeb.talon.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.unifiedpush.android.connector.MessagingReceiver
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * UnifiedPush dispatch receiver. Subclass of the connector library's
 * [MessagingReceiver] — the manifest registers it with the right
 * intent filters, and the lib calls into our overrides for each
 * lifecycle event.
 *
 * Endpoint storage: [onNewEndpoint] writes the URL to a private
 * SharedPreferences entry. [UnifiedPushTokenProvider.token()] reads
 * it back. The relay's /register flow gets the URL handed up the
 * stack as the device's `pushEndpoint`.
 *
 * Push handling: [onMessage] receives the relay's hint-only payload
 * (`{event, patp, whom, id}`) and triggers a notification post.
 * Actual message text is pulled from the ship via SSE on wake; we
 * never trust whatever's in the push body to be the full content.
 *
 * AndroidManifest.xml needs:
 *   <receiver android:name=".notify.TalonMessagingReceiver"
 *             android:exported="true">
 *     <intent-filter>
 *       <action android:name="org.unifiedpush.android.connector.MESSAGE"/>
 *       <action android:name="org.unifiedpush.android.connector.UNREGISTERED"/>
 *       <action android:name="org.unifiedpush.android.connector.NEW_ENDPOINT"/>
 *       <action android:name="org.unifiedpush.android.connector.REGISTRATION_FAILED"/>
 *     </intent-filter>
 *   </receiver>
 */
class TalonMessagingReceiver : MessagingReceiver() {

    override fun onNewEndpoint(context: Context, endpoint: PushEndpoint, instance: String) {
        Log.i(TAG, "new endpoint for instance=$instance: ${endpoint.url.take(48)}…")
        // The ship's own pushes are another registration, with its own endpoint.
        if (instance == ShipPushes.INSTANCE) return ShipPushes.onEndpoint(context, endpoint)
        cacheEndpoint(context, endpoint.url)
    }

    override fun onUnregistered(context: Context, instance: String) {
        Log.i(TAG, "unregistered instance=$instance")
        if (instance == ShipPushes.INSTANCE) return ShipPushes.onUnregistered(context)
        clearEndpoint(context)
    }

    override fun onRegistrationFailed(
        context: Context,
        reason: org.unifiedpush.android.connector.FailedReason,
        instance: String,
    ) {
        Log.w(TAG, "registration failed for instance=$instance: $reason")
    }

    override fun onMessage(context: Context, message: PushMessage, instance: String) {
        if (instance == ShipPushes.INSTANCE) return ShipPushes.onMessage(context, message)
        // Hint-only payload from the Talon relay:
        //   { "event": "new-message", "patp": "...", "whom": "...",
        //     "id": "..." }
        // We don't trust the body to carry actual message text — by
        // design, only the whom + ship + id come through the relay.
        // Post a generic "new message in <conversation>" so the
        // user sees something immediately; the tap-intent opens
        // MainActivity which restores the SSE channel and pulls
        // real content into the chat.
        val parsed = runCatching {
            Json.parseToJsonElement(message.content.decodeToString()) as? JsonObject
        }.getOrNull()
        val whom = parsed?.get("whom")?.jsonPrimitive?.content
        val patp = parsed?.get("patp")?.jsonPrimitive?.content
        val eventId = parsed?.get("id")?.jsonPrimitive?.content
        val event = parsed?.get("event")?.jsonPrimitive?.content
        Log.i(TAG, "push received event=$event patp=$patp whom=$whom id=$eventId")

        // A ring is the one push that isn't a message: it rings rather
        // than pings, and it carries the caller instead of a whom.
        if (event == "ring") {
            val from = parsed?.get("from")?.jsonPrimitive?.content
            if (from.isNullOrBlank() || eventId.isNullOrBlank()) return
            // This receiver is exported and UnifiedPush broadcasts are
            // unauthenticated — any app on the phone can forge one, and
            // a forged ring is a fake incoming call over the lock
            // screen. The relay only ever rings us for a ship this
            // device is signed into, so anything else is dropped.
            val knownShips = (context.applicationContext as? io.nisfeb.talon.TalonApplication)
                ?.allShipsFlow?.value.orEmpty()
            if (patp == null || patp !in knownShips) {
                Log.w(TAG, "dropping ring for a ship we are not signed into: $patp")
                return
            }
            // The last names this process saw: a ring waits for no database.
            val name = pushNames(
                patp,
                (context.applicationContext as? io.nisfeb.talon.TalonApplication)?.activeShipFlow?.value,
                io.nisfeb.talon.ui.LastContactMap.value,
            ).displayName(from)
            io.nisfeb.talon.Notifications.showIncomingCall(context, from, eventId, name)
            // Telecom hears about the ring from here, not only from the
            // app: this is the path a phone in a pocket takes, and a
            // call telecom never saw is not in the call log. If the
            // app answers, it adopts this connection by id.
            if (io.nisfeb.talon.call.ModernTelecom.active) {
                io.nisfeb.talon.call.ModernTelecom.start(context, eventId, from, name, incoming = true)
            } else {
                io.nisfeb.talon.call.TalonTelecom.startIncoming(context, eventId, from, name)
            }
            return
        }

        // The ring's undoing: the caller hung up (or another of the
        // user's clients answered) before this device did anything.
        // Without it a dead process rang the full 45 seconds after
        // the caller had already given up. Id-matched so a late
        // cancel for a previous call leaves a newer ring alone.
        if (event == "ring-cancel") {
            // Only for a call this device rang or is in: the ship's trunk
            // cancels on every device it has, one that never rang too.
            val ours = !eventId.isNullOrBlank() && io.nisfeb.talon.notify.ringCancelIsOurs(
                eventId,
                io.nisfeb.talon.Notifications.shownCall,
                io.nisfeb.talon.Notifications.liveCallId(),
                eventId.takeIf {
                    io.nisfeb.talon.call.TalonTelecom.connection(it) != null || io.nisfeb.talon.call.ModernTelecom.call(it) != null
                },
            )
            if (!ours) Log.i(TAG, "ring-cancel for a call this device never rang: $eventId")
            if (ours && eventId != null) {
                val reason = parsed?.get("reason")?.jsonPrimitive?.content
                io.nisfeb.talon.Notifications.ringCancelled(context, eventId, reason)
                io.nisfeb.talon.call.TalonTelecom.connection(eventId)
                    ?.endRing(answeredElsewhere = reason == "answered")
                io.nisfeb.talon.call.ModernTelecom.call(eventId)
                    ?.endRing(answeredElsewhere = reason == "answered")
                // The far end hung up an answered call. The event stream
                // normally carries that, but it is asleep exactly when
                // the app is backgrounded; the push is the reliable copy.
                if (reason == "hangup") {
                    io.nisfeb.talon.call.TalonTelecom.hooks?.controls(eventId)?.onDisconnect()
                }
            }
            return
        }

        // The ship's test push (its own %trunk, wire 11): it says the
        // ship can reach this device, and is never shown. The move off
        // the public relay waits for exactly this nonce.
        if (event == "push-test") {
            parsed?.get("nonce")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?.let { io.nisfeb.talon.notify.PushTestNonces.received(it) }
            return
        }

        // From another app on the ship (calendar, orrery), through its %trunk:
        // shown by its tag, as grubbery's web push is.
        if (event == "notice") {
            parsed?.let { io.nisfeb.talon.notify.noticeOf(it) }?.let { io.nisfeb.talon.Notifications.showShipPush(context, it) }
            return
        }

        if (whom.isNullOrBlank()) return
        // Read to the end on some client: its notifications go, as Tlon's
        // %notify dismisses them. Only that ship's: the same whom on
        // another is another conversation. A forged one clears a
        // notification and nothing else.
        if (event == "read") {
            io.nisfeb.talon.Notifications.cancelAllForChat(context, whom, forShip = patp)
            return
        }
        // On screen right now: the app already shows it, and a
        // notification would only need clearing. Only when the push is
        // for the ship actually signed in, though — the same whom open
        // on ship A says nothing about ship B's conversation of that
        // name. A missing patp (older relay) keeps the old behaviour.
        val app = context.applicationContext as? io.nisfeb.talon.TalonApplication
        val currentShip = app?.activeShipFlow?.value
        if ((patp == null || patp == currentShip) &&
            whom in io.nisfeb.talon.notify.ShownConversation.keys
        ) return
        // The relay sends the globally-unique post id as `id`
        // (`<author>/<128-bit-id>` from the activity event's
        // dm-post.key.id). Plumbing it through as `postId` makes the
        // tap-intent anchor on that specific message via
        // EXTRA_SCROLL_TO_MESSAGE rather than just opening the chat
        // at its tail. Older relay builds emitted a numeric channel-
        // local id which won't resolve to a real row — passing it
        // anyway is harmless (MainActivity scrolls to "best effort"
        // and falls back to the chat's newest message).
        // Named as the app names it, from the database: off this thread,
        // and kept alive with goAsync until the notification is up.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val names = app?.let { a -> runCatching { withTimeoutOrNull(5_000) { a.db.contactMapNow() } }.getOrNull() }
                    ?: io.nisfeb.talon.ui.ContactMap(alwaysPatp = io.nisfeb.talon.ui.ShipNames.alwaysPatp.value)
                val n = pushHintNotification(whom, patp, currentShip, names)
                io.nisfeb.talon.Notifications.showMessage(
                    context = context,
                    whom = whom,
                    postId = eventId?.takeIf { it.isNotBlank() },
                    // A reply's parent, from a relay (or a ship's trunk)
                    // that sends one: the tap opens the thread on it.
                    parentId = parsed?.get("parent")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                    // A tap switches to the ship this is for, because the
                    // same whom on another one is a different conversation.
                    forShip = patp,
                    title = n.title,
                    body = n.body,
                    sentMs = System.currentTimeMillis(),
                )
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "TalonMessagingReceiver"
        private const val PREFS = "talon.unifiedpush"
        private const val KEY_ENDPOINT = "endpoint"

        fun cachedEndpoint(context: Context): String? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ENDPOINT, null)

        internal fun cacheEndpoint(context: Context, url: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_ENDPOINT, url).apply()
        }

        fun clearEndpoint(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_ENDPOINT).apply()
        }

        /**
         * Stream of the cached endpoint URL, emitting the current
         * value on subscription and again whenever
         * [cacheEndpoint] / [clearEndpoint] runs (or any other
         * write to the same prefs file). Lets the registration
         * flow await the distributor's NEW_ENDPOINT broadcast
         * without polling — see UnifiedPushTokenProvider.token().
         */
        fun endpointFlow(context: Context): Flow<String?> = callbackFlow {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            trySend(prefs.getString(KEY_ENDPOINT, null))
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
                if (key == KEY_ENDPOINT) trySend(p.getString(KEY_ENDPOINT, null))
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }
    }
}
