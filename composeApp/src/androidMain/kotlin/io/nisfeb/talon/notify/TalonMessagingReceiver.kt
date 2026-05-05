package io.nisfeb.talon.notify

import android.content.Context
import android.content.SharedPreferences
import io.nisfeb.talon.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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
        cacheEndpoint(context, endpoint.url)
    }

    override fun onUnregistered(context: Context, instance: String) {
        Log.i(TAG, "unregistered instance=$instance")
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
        Log.i(TAG, "push received patp=$patp whom=$whom id=$eventId")

        if (whom.isNullOrBlank()) return
        val title = patp ?: "Talon"
        val body = "New activity in $whom"
        // The relay sends the globally-unique post id as `id`
        // (`<author>/<128-bit-id>` from the activity event's
        // dm-post.key.id). Plumbing it through as `postId` makes the
        // tap-intent anchor on that specific message via
        // EXTRA_SCROLL_TO_MESSAGE rather than just opening the chat
        // at its tail. Older relay builds emitted a numeric channel-
        // local id which won't resolve to a real row — passing it
        // anyway is harmless (MainActivity scrolls to "best effort"
        // and falls back to the chat's newest message).
        io.nisfeb.talon.Notifications.showMessage(
            context = context,
            whom = whom,
            postId = eventId?.takeIf { it.isNotBlank() },
            title = title,
            body = body,
            sentMs = System.currentTimeMillis(),
        )
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
