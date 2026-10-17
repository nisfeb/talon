package io.nisfeb.talon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.nisfeb.talon.ai.Loops
import io.nisfeb.talon.util.Log
import kotlinx.coroutines.CancellationException

/**
 * Wakes when a loop is due and hands the run to [LoopWorker] at once. A
 * run is an agent and its HTTP, minutes at worst, far past what a
 * receiver may hold the process for: under goAsync() the process could
 * be killed mid-run, the result never posted, the run never retried.
 *
 * Android-only: no desktop analog — depends on AlarmManager. Desktop
 * runs the same LoopRunner on a while-open ticker (App.kt).
 */
class LoopAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Loops.ACTION_LOOP_FIRE) return
        // Contained: a throw here crashes the process in the background, on every alarm.
        runCatching { LoopWorker.enqueue(context) }
            .onFailure { Log.w("LoopAlarmReceiver", "could not hand the loop fire to its worker", it) }
    }
}

/**
 * One alarm's run: every due loop, then the next wake-up armed
 * ([Loops.runDueNow]). Expedited, so it runs through Doze as the alarm
 * did; WorkManager holds the wake lock. Never retried: each loop is
 * stamped as it starts, so a second go would find nothing due, and a
 * run cut short says so in its own result (LoopRunner.runLoop).
 *
 * Android-only: no desktop analog, as [LoopAlarmReceiver].
 */
class LoopWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? TalonApplication ?: return Result.success()
        try {
            app.loops.runDueNow()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "loop fire failed", t)
        }
        return Result.success()
    }

    /** Below Android 12 an expedited job runs as a foreground service, which needs a row in the shade. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        Notifications.ensureChannel(applicationContext)
        return ForegroundInfo(
            NOTIFICATION_ID,
            NotificationCompat.Builder(applicationContext, Notifications.CHANNEL_SYNC)
                .setSmallIcon(R.drawable.ic_stat_talon)
                .setContentTitle("Running your loops")
                .setSilent(true)
                .setOngoing(true)
                .build(),
        )
    }

    companion object {
        private const val TAG = "LoopWorker"
        private const val UNIQUE_NAME = "talon-loop-fire"
        private const val NOTIFICATION_ID = 7708

        /**
         * After any run under way, not instead of it: the run re-arms the
         * alarm before it ends, and a wake-up already due fires while it
         * is still running. KEEP would drop that fire; REPLACE would cut
         * the run short.
         */
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<LoopWorker>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build(),
            )
        }
    }
}
