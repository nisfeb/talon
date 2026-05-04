package io.nisfeb.talon

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.nisfeb.talon.util.Log

/**
 * Foreground service whose only job is to keep the app process alive
 * so TlonChatRepo's SSE subscription keeps firing and new-message
 * notifications fire instantly even when the activity isn't on screen.
 *
 * We don't run any work inside the service — the repo lives on the
 * Application and is pinned here via the process's implicit lifetime.
 * Android will still kill us under memory pressure; that's OK, the
 * repo is idempotent and the next app open re-establishes the channel.
 */
class TalonSyncService : Service() {

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var screenOnReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        registerNetworkCallback()
        registerScreenOnReceiver()
    }

    override fun onDestroy() {
        unregisterNetworkCallback()
        unregisterScreenOnReceiver()
        super.onDestroy()
    }

    /**
     * Force-reconnect when a usable network arrives. Catches the
     * common Wi-Fi → cell hand-off case where the SSE socket dies
     * silently and the watchdog's 90s idle timer is the only thing
     * that recovers it. NetworkCallback is significantly faster.
     */
    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "network available; force-reconnect")
                applicationOrNull()?.repo?.forceReconnect()
            }
        }
        runCatching { cm.registerNetworkCallback(request, callback) }
            .onSuccess { networkCallback = callback }
            .onFailure { Log.w(TAG, "registerNetworkCallback failed", it) }
    }

    private fun unregisterNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val cb = networkCallback ?: return
        runCatching { cm.unregisterNetworkCallback(cb) }
        networkCallback = null
    }

    /**
     * Opportunistic catch-up when the user wakes the screen. Cheap
     * (no channel reopen — just the activity re-scry) and closes the
     * gap doze sometimes opens even when the foreground service kept
     * the socket up. Doesn't fire on every screen-on event because
     * forceReconnect's 3s debounce + the repo's own catch-up cadence
     * cap the cost.
     */
    private fun registerScreenOnReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != Intent.ACTION_SCREEN_ON) return
                applicationOrNull()?.repo?.catchUp()
            }
        }
        val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
        }
            .onSuccess { screenOnReceiver = receiver }
            .onFailure { Log.w(TAG, "registerScreenOnReceiver failed", it) }
    }

    private fun unregisterScreenOnReceiver() {
        val r = screenOnReceiver ?: return
        runCatching { unregisterReceiver(r) }
        screenOnReceiver = null
    }

    private fun applicationOrNull(): TalonApplication? =
        application as? TalonApplication

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildOngoing()
        when {
            // Android 14+ (API 34+): SPECIAL_USE has no timeout. dataSync
            // is hard-capped at 6h/day on Android 15+ which kills the
            // sync mid-day with a ForegroundServiceDidNotStopInTime
            // crash — wrong fit for a chat client that can't use FCM.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            else -> startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    private fun buildOngoing(): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, Notifications.CHANNEL_SYNC)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Talon")
            .setContentText("Connected")
            .setContentIntent(pending)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 100
        private const val TAG = "TalonSyncService"

        fun start(context: Context) {
            val intent = Intent(context, TalonSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TalonSyncService::class.java))
        }
    }
}
