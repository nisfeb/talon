package io.nisfeb.talon.notify

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * Android-side [SystemNotificationProbe]. Reads the three signals
 * that, in our experience, account for most "I never got the
 * notification" reports:
 *
 *  1. POST_NOTIFICATIONS (Android 13+) — denied silently swallows
 *     every notification the app posts.
 *  2. Battery-optimization exempt — without this, Doze freezes the
 *     foreground service indefinitely on idle screens.
 *  3. Background-restricted — the OS-level kill switch that some
 *     OEMs flip when the user "force stops" the app even once. Even
 *     FCM pushes don't reach a background-restricted app.
 */
class AndroidSystemNotificationProbe(private val context: Context) : SystemNotificationProbe {

    override fun snapshot(): SystemNotificationState =
        SystemNotificationState(
            notificationsAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                NotificationManagerCompat.from(context).areNotificationsEnabled()
            else null,
            batteryOptimizationsExempt = readBatteryExempt(),
            backgroundRestricted = readBackgroundRestricted(),
        )

    private fun readBatteryExempt(): Boolean? {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return null
        return runCatching {
            pm.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrNull()
    }

    private fun readBackgroundRestricted(): Boolean? {
        // ActivityManager.isBackgroundRestricted is API 28+ AND only
        // present on the activity-manager-as-context, not the app
        // context — getSystemService gives the right one either way.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return null
        return runCatching { am.isBackgroundRestricted }.getOrNull()
    }

    override fun openBatteryOptimizationSettings(): Boolean {
        // ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS is the docs-
        // approved way to ask. Some OEMs reject it (Xiaomi has been
        // known to throw); fall back to the per-app battery-saver
        // page on those.
        val pkg = context.packageName
        val intents = listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$pkg")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        for (i in intents) {
            if (runCatching { context.startActivity(i) }.isSuccess) return true
        }
        return false
    }

    override fun openAppDetailsSettings(): Boolean {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
