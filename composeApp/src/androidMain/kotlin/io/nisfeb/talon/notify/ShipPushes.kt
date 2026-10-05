package io.nisfeb.talon.notify

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.nisfeb.talon.Notifications
import io.nisfeb.talon.TalonApplication
import io.nisfeb.talon.orrery.LeaveAlarm
import io.nisfeb.talon.orrery.leaveKeyOfTag
import io.nisfeb.talon.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * The ship's own pushes on the phone: a second UnifiedPush registration,
 * [INSTANCE], subscribed to the active ship's grubbery web push the way a
 * browser subscribes, so calendar reminders and orrery's "Leave in 10 min"
 * reach the phone with the app closed. The relay's registration is
 * another instance and carries only its hints.
 *
 * Android-only: UnifiedPush is Android's. iOS port pending: APNs, which
 * grubbery's web push cannot reach without a bridge. Desktop has no
 * analog: the desktop is a browser's to subscribe.
 *
 * The distributor encrypts nothing itself: the ship encrypts to the
 * keys the connector made for [INSTANCE] (RFC 8291) and the connector
 * decrypts, so a push that did not decrypt is dropped.
 */
object ShipPushes {
    const val INSTANCE = "ship"
    private const val PREFS = "talon.shippush"
    private const val KEY_ENDPOINT = "endpoint"
    private const val KEY_P256DH = "p256dh"
    private const val KEY_AUTH = "auth"
    private const val WORK = "talon-ship-push-subscribe"
    private const val TAG = "ShipPushes"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun subKey(ship: String) = "sub:$ship"

    /** The endpoint and the ship's id for it, as subscribed: "<endpoint>\n<sub id>". */
    internal fun subscription(ctx: Context, ship: String): Pair<String, String>? =
        prefs(ctx).getString(subKey(ship), null)?.split('\n', limit = 2)?.takeIf { it.size == 2 }?.let { it[0] to it[1] }

    /**
     * From every start: register [INSTANCE] when it is not yet, else make
     * sure the active ship is subscribed. Nothing without a distributor
     * or a ship; a ship without grubbery's push answers 404 and is left.
     */
    fun ensure(app: TalonApplication) {
        val session = app.sessionStore.active() ?: return
        val distributors = runCatching { UnifiedPush.getDistributors(app) }.getOrDefault(emptyList())
        if (distributors.isEmpty()) return
        val endpoint = prefs(app).getString(KEY_ENDPOINT, null)
        if (endpoint != null) {
            if (subscription(app, session.ship)?.first != endpoint) subscribeLater(app)
            return
        }
        scope.launch {
            runCatching {
                val vapid = ShipPushApi(app.session.http, session.shipUrl).vapidKey()
                val saved = UnifiedPush.getSavedDistributor(app)
                if (saved == null || saved !in distributors) UnifiedPush.saveDistributor(app, distributors.first())
                UnifiedPush.register(app, INSTANCE, null, vapid)
            }.onFailure { Log.i(TAG, "ship pushes not set up: ${it.message}") }
        }
    }

    fun onEndpoint(ctx: Context, endpoint: PushEndpoint) {
        val keys = endpoint.pubKeySet ?: return Log.w(TAG, "the distributor gave no keys; the ship cannot encrypt to it")
        prefs(ctx).edit()
            .putString(KEY_ENDPOINT, endpoint.url).putString(KEY_P256DH, keys.pubKey).putString(KEY_AUTH, keys.auth)
            .apply()
        subscribeLater(ctx)
    }

    fun onUnregistered(ctx: Context) {
        val p = prefs(ctx)
        p.edit().clear().apply()
    }

    fun onMessage(ctx: Context, message: PushMessage) {
        if (!message.decrypted) return Log.w(TAG, "a ship push that did not decrypt was dropped")
        val push = parseShipPush(message.content.decodeToString())
            ?: return Log.w(TAG, "a ship push with no title was dropped")
        Log.i(TAG, "ship push tag=${push.tag}")
        Notifications.showShipPush(ctx, push)
        // The push came: the alarm kept in case it did not stands down, and
        // the trip begins where Android lets a push start it (Talon in
        // front); otherwise its own alarm, at the same moment, does.
        leaveKeyOfTag(push.tag)?.let {
            LeaveAlarm.done(ctx, it)
            io.nisfeb.talon.orrery.Trips.start(ctx)
        }
    }

    /**
     * Signing out of [ship]: the ship stops pushing here. Best effort. The
     * saved cookie goes with the request itself, since signing out clears
     * the session's own before this request would leave.
     */
    fun forget(app: TalonApplication, ship: String) {
        val sub = subscription(app, ship) ?: return
        prefs(app).edit().remove(subKey(ship)).apply()
        val saved = app.sessionStore.all().firstOrNull { it.ship == ship } ?: return
        val http = signedOutClient.config {
            install(io.ktor.client.plugins.DefaultRequest) { headers.append("Cookie", "${saved.cookieName}=${saved.cookieValue}") }
        }
        scope.launch {
            runCatching { ShipPushApi(http, saved.shipUrl).unsubscribe(sub.second) }
                .onFailure { Log.i(TAG, "could not unsubscribe from $ship: ${it.message}") }
        }
    }

    private val signedOutClient by lazy { io.nisfeb.talon.util.createAppHttpClient() }

    private fun subscribeLater(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            WORK, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ShipPushSubscribeWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }

    internal suspend fun subscribeActive(app: TalonApplication) {
        val session = app.sessionStore.active() ?: return
        val p = prefs(app)
        val endpoint = p.getString(KEY_ENDPOINT, null) ?: return
        val old = subscription(app, session.ship)
        if (old?.first == endpoint) return
        val api = ShipPushApi(app.session.http, session.shipUrl)
        val subId = api.subscribe(endpoint, p.getString(KEY_P256DH, null) ?: return, p.getString(KEY_AUTH, null) ?: return)
        p.edit().putString(subKey(session.ship), "$endpoint\n$subId").apply()
        Log.i(TAG, "${session.ship} pushes here now")
        // A new endpoint replaces the old: the ship stops pushing to the one gone.
        if (old != null && old.second != subId) runCatching { api.unsubscribe(old.second) }
    }
}

/** Subscribes the active ship to this phone's [ShipPushes.INSTANCE] endpoint. */
class ShipPushSubscribeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? TalonApplication ?: return Result.success()
        return runCatching { ShipPushes.subscribeActive(app) }.fold(
            onSuccess = { Result.success() },
            onFailure = {
                Log.w("ShipPushes", "subscribe failed: ${it.message}")
                // The ship said no (a 4xx): asking again says no again.
                val refused = (it as? ShipPushRefused)?.status in 400..499
                if (!refused && runAttemptCount < 3) Result.retry() else Result.success()
            },
        )
    }
}
