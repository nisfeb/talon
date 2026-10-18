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
 * Loops arm from a suspend DB read, so the broadcast is held open with
 * goAsync() plus a wake lock while the re-arm is awaited — otherwise a
 * freshly-booted process can be reclaimed before the alarm is actually
 * set, defeating the whole point of this receiver.
 *
 * On boot it also starts [TalonSyncService] where no push reaches this
 * phone ([TalonSyncService.startIfNeeded]): until Talon is opened again,
 * nothing else would hear the ship.
 *
 * See spec §Error handling: "Reboot → BootReceiver re-arms."
 *
 * Android-only: no desktop analog. A computer's Talon is opened by the
 * owner and runs loops only while open (App.kt's ticker); nothing is
 * armed to survive a reboot.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                val app = context.applicationContext as TalonApplication
                // A start from boot is one Android allows from the background.
                if (intent.action == Intent.ACTION_BOOT_COMPLETED) TalonSyncService.startIfNeeded(app)
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
                    // Same containment as the coroutine's: rethrowing
                    // here crashes the process at boot, on every boot.
                    Log.w("BootReceiver", "could not arm the loop re-arm", t)
                    runCatching { pending.finish() }
                }
            }
        }
    }
}
