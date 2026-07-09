package io.nisfeb.talon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.nisfeb.talon.ai.Loops
import io.nisfeb.talon.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Wakes when a loop is due. Hands off to the Loops facade in goAsync()
 * mode (under a wake lock) so the agent/HTTP work isn't cut off by the
 * receiver's sync limit. [Loops.runDueNow] runs every due loop and
 * re-arms the next wake-up itself, so this receiver only manages the
 * goAsync + wake-lock lifecycle.
 *
 * Android-only: no desktop analog — depends on AlarmManager. Mirrors
 * DigestAlarmReceiver.
 */
class LoopAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Loops.ACTION_LOOP_FIRE) return
        val app = context.applicationContext as TalonApplication
        val pending = goAsync()
        try {
            val wakeLock = app.loops.acquireWakeLock("loop-fire")
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    // Contain failures: an uncaught throw in a root coroutine
                    // with no exception handler kills the whole app process
                    // in the background (a transient Room/session failure
                    // right after boot would crash us on every alarm).
                    runCatching { app.loops.runDueNow() }
                        .onFailure { Log.w("LoopAlarmReceiver", "loop fire failed", it) }
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
