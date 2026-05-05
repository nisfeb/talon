package io.nisfeb.talon.notify

import android.content.Context
import org.unifiedpush.android.connector.UnifiedPush
import io.nisfeb.talon.util.Log

/**
 * [PushTokenProvider] backed by UnifiedPush. Asks the user's local
 * UnifiedPush distributor (ntfy / NextPush / Conversations / …)
 * for an endpoint URL and caches it in SharedPreferences.
 *
 * Endpoint lifecycle:
 *   1. token() called for the first time. We check SharedPrefs for a
 *      cached endpoint from a previous session.
 *   2. Cache miss → call UnifiedPush.registerApp(). The endpoint
 *      arrives asynchronously via TalonMessagingReceiver, which
 *      writes it to SharedPrefs. token() in this call returns null
 *      because we don't block; the next token() call sees it.
 *   3. Distributor reset (rare) → onNewEndpoint fires again with a
 *      different URL. SharedPrefs gets overwritten. The Settings
 *      panel's "Re-register" button picks up the new endpoint on
 *      its next call.
 *
 * No-distributor case: getDistributors(context) returns empty.
 * token() returns null and the registration flow surfaces a
 * "install ntfy or NextPush" message.
 */
class UnifiedPushTokenProvider(private val context: Context) : PushTokenProvider {

    override val platform: String = "unifiedpush"

    override suspend fun token(): String? {
        val cached = TalonMessagingReceiver.cachedEndpoint(context)
        if (cached != null) return cached

        // No cache. If the user already picked a distributor, ask
        // it for a fresh endpoint; otherwise return null and let
        // the UI prompt them to install one.
        val distributors = runCatching { UnifiedPush.getDistributors(context) }
            .getOrDefault(emptyList())
        if (distributors.isEmpty()) {
            Log.i(TAG, "no UnifiedPush distributor installed")
            return null
        }
        // tryUseCurrentOrDefaultDistributor picks one we've used
        // before, or the first available. Returns true on success;
        // we still won't have an endpoint synchronously — the
        // distributor calls back via TalonMessagingReceiver.
        runCatching {
            UnifiedPush.tryUseCurrentOrDefaultDistributor(context) { ok ->
                if (ok) UnifiedPush.registerApp(context, INSTANCE)
            }
        }.onFailure { Log.w(TAG, "UnifiedPush register failed", it) }
        return null
    }

    fun unregister() {
        runCatching { UnifiedPush.unregisterApp(context, INSTANCE) }
        TalonMessagingReceiver.clearEndpoint(context)
    }

    companion object {
        private const val TAG = "UnifiedPushTokenProvider"
        /** UnifiedPush "instance" — opaque per-app id. We only need
         *  one (Talon doesn't multiplex multiple notification
         *  streams) so a static value is fine. */
        const val INSTANCE = "talon"
    }
}
