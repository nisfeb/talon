package io.nisfeb.talon.notify

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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

    override suspend fun diagnose(): DistributorReport {
        // Two parallel queries against the same manifest action. They
        // should agree — disagreement is the diagnostic signal.
        //
        // Path 1: ask the platform directly. Anything in this list is
        // a receiver Talon's UID can observe via `<queries>` and
        // package visibility.
        val pmList = runCatching {
            val intent = Intent(ACTION_REGISTER)
            @Suppress("QueryPermissionsNeeded")
            context.packageManager.queryBroadcastReceivers(intent, 0)
        }.getOrDefault(emptyList())
        val pmDescriptions = pmList.map { ri ->
            val pkg = ri.activityInfo?.packageName ?: "?"
            val cls = ri.activityInfo?.name?.substringAfterLast('.') ?: "?"
            val exported = ri.activityInfo?.exported == true
            "$pkg/$cls exported=$exported"
        }

        // Path 2: ask the connector library. It runs the same query
        // then strips non-exported receivers from foreign packages,
        // and excludes Talon-as-distributor when an embedded FCM
        // distributor isn't a fit.
        val connectorList = runCatching { UnifiedPush.getDistributors(context) }
            .getOrDefault(emptyList())

        val cached = TalonMessagingReceiver.cachedEndpoint(context)
        val cachedPreview = cached?.take(48)?.let { "$it…" }

        val note = buildString {
            if (pmList.isEmpty()) {
                append("PackageManager returned 0 — Talon's <queries> block ")
                append("doesn't match any installed receiver, OR no app is ")
                append("registering as a distributor right now.")
            } else if (connectorList.isEmpty() && pmList.isNotEmpty()) {
                append("PackageManager sees ${pmList.size} receiver(s) but ")
                append("the connector kept 0 — every visible receiver is ")
                append("exported=false. Install a real distributor like ")
                append("ntfy / NextPush; the receivers above are private to ")
                append("their owner apps.")
            } else if (connectorList.size > 1) {
                append("Multiple distributors visible. Talon will pick the ")
                append("first one the connector returns; uninstall the ones ")
                append("you don't want.")
            }
        }

        Log.i(
            TAG,
            "diagnose: pm=${pmList.size} connector=${connectorList.size} " +
                "cached=${cached != null}",
        )
        return DistributorReport(
            byPackageManager = pmDescriptions,
            byConnector = connectorList,
            cachedEndpoint = cachedPreview,
            note = note,
        )
    }

    companion object {
        private const val TAG = "UnifiedPushTokenProvider"
        /** UnifiedPush "instance" — opaque per-app id. We only need
         *  one (Talon doesn't multiplex multiple notification
         *  streams) so a static value is fine. */
        const val INSTANCE = "talon"
        private const val ACTION_REGISTER =
            "org.unifiedpush.android.distributor.REGISTER"
    }
}
