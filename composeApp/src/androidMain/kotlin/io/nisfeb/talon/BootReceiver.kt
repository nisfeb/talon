package io.nisfeb.talon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.nisfeb.talon.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Re-arm scheduled alarms on boot or timezone change. Without this,
 * AlarmManager forgets the schedule across a reboot, and time-of-day
 * alarms fire at the wrong local time after travel.
 *
 * Daily digest arms synchronously (it reads an in-memory settings flow),
 * so a plain call is enough. Loops arm from a suspend DB read, so we hold
 * the broadcast open with goAsync() + a wake lock and await the re-arm —
 * otherwise a freshly-booted process can be reclaimed before the alarm is
 * actually set, defeating the whole point of this receiver.
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
                val pending = goAsync()
                try {
                    val wakeLock = app.loops.acquireWakeLock("loop-boot-rearm")
                    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                        try {
                            // Contain failures: an uncaught throw in a root
                            // coroutine kills the app process — at boot that
                            // means a crash on EVERY boot until it clears.
                            runCatching { app.loops.rescheduleNow() }
                                .onFailure { Log.w("BootReceiver", "loop re-arm failed", it) }
                        } finally {
                            runCatching { wakeLock.release() }
                            runCatching { pending.finish() }
                        }
                    }
                } catch (t: Throwable) {
                    runCatching { pending.finish() }
                    throw t
                }
            }
        }
    }
}
