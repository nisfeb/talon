package io.nisfeb.talon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-arm the daily digest alarm on boot or timezone change. Without
 * this, AlarmManager forgets the schedule when the device reboots,
 * and the digest fires at the wrong local time after travel.
 *
 * See spec §Error handling: "Reboot → BootReceiver re-arms."
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                val app = context.applicationContext as TalonApplication
                runCatching { app.dailyDigest.scheduleNext() }
            }
        }
    }
}
