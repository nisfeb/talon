package io.nisfeb.talon

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.nisfeb.talon.util.Log
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * Periodic Layer-3 reconcile that survives force-stop and app
 * standby buckets. WorkManager's minimum interval is 15 minutes, so
 * this is a backstop — not a replacement for the SSE channel or the
 * push relay. It catches the cases those two miss:
 *
 *   - User force-stopped Talon out of recents. Foreground service
 *     gone; WorkManager keeps running until the user actually does
 *     "Force stop" from app info (rarer).
 *   - Doze + Battery Saver have throttled the foreground service
 *     into "frozen" state. WorkManager still gets a budget under
 *     "expedited" / off-Doze maintenance windows.
 *   - Process OOM-killed; SSE is gone but next maintenance window
 *     re-runs us.
 *
 * The work is cheap: scry %activity, diff against local, post any
 * notifications the SSE / push relay missed. ConnectionPool worth
 * of work, but bounded — we delay() up to 8 seconds for an open
 * channel before giving up, so even a wedged ship doesn't burn
 * the worker's runtime budget.
 */
class CatchUpWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? TalonApplication ?: return Result.success()
        // No active ship → nothing to catch up on. Don't retry —
        // WorkManager will run us again at the next 15-minute slot.
        if (app.activeShipFlow.value == null) return Result.success()

        // Wait briefly for the channel + sync state to come up
        // (notificationHealth is a useful signal here). Caps at 8s
        // so we don't camp on the worker's 10-min runtime budget.
        // notificationHealth.sseConnected is set true when the SSE
        // collect loop opens; if it never flips, the watchdog / app-
        // resume reconnect owns recovery.
        var waited = 0L
        while (!app.notificationHealth.sseConnected.value && waited < 8_000L) {
            delay(500L)
            waited += 500L
        }
        if (!app.notificationHealth.sseConnected.value) {
            Log.i(TAG, "no live channel after wait; deferring")
            return Result.retry()
        }

        runCatching { app.repo.catchUp() }
            .onFailure { Log.w(TAG, "catchUp failed: ${it.message}") }

        return Result.success()
    }

    companion object {
        private const val TAG = "CatchUpWorker"
        private const val UNIQUE_NAME = "talon-catchup"

        /**
         * Idempotent scheduler — call from app startup. KEEP policy
         * means subsequent calls don't reset the schedule, so app
         * restarts don't push the next run by 15 minutes each time.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CatchUpWorker>(
                15, TimeUnit.MINUTES,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
