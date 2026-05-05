package io.nisfeb.talon.notify

import android.content.Context
import io.nisfeb.talon.util.Log
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
        // Hint-only payload from the Talon relay. We don't render
        // the message content from the push — pull it via SSE on
        // wake. For now, post a generic "new message" notification
        // with the conversation label so the user sees something
        // immediately. Future: kick the SSE catchUp here so the
        // tap-through opens the actual message.
        val parsed = runCatching {
            Json.parseToJsonElement(message.content.decodeToString()) as? JsonObject
        }.getOrNull()
        val whom = parsed?.get("whom")?.jsonPrimitive?.content
        Log.i(TAG, "push received whom=$whom")
        // TODO: route through the existing AndroidNotifier so the
        // notification's tap-intent opens the right chat. For v1
        // we just log — the SSE / WorkManager paths still post.
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
    }
}
