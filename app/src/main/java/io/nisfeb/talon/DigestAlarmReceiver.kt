package io.nisfeb.talon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.nisfeb.talon.ai.DailyDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Wakes once a day at the user-configured time. Hands off to the
 * DailyDigest facade in `goAsync()` mode so the AI/HTTP work isn't
 * cut off by the receiver's 10s sync limit. Re-arms tomorrow's
 * alarm in `finally` regardless of pipeline outcome — quiet days,
 * AI failures, and exceptions all still re-schedule.
 *
 * See spec §Schedule + §Error handling.
 */
class DigestAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DailyDigest.ACTION_DAILY_DIGEST_FIRE) return
        val app = context.applicationContext as TalonApplication
        val pending = goAsync()
        try {
            val wakeLock = app.dailyDigest.acquireWakeLock("digest-fire")
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    app.dailyDigest.generateAndNotifyNow("alarm")
                } finally {
                    runCatching { app.dailyDigest.scheduleNext() }
                    runCatching { wakeLock.release() }
                    runCatching { pending.finish() }
                }
            }
        } catch (t: Throwable) {
            // acquireWakeLock or coroutine launch threw before the launch
            // could install its finally — make sure the goAsync receiver
            // doesn't hang the system waiting for finish().
            runCatching { pending.finish() }
            throw t
        }
    }
}
