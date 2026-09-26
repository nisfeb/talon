package io.nisfeb.talon

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.nisfeb.talon.orrery.CloudTriage
import io.nisfeb.talon.orrery.OrreryRepo
import io.nisfeb.talon.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.TimeUnit

/**
 * The orrery pass while the phone is charging and idle, so the model
 * reads the day's calls without the app open and without the battery
 * paying for it. Android-only: no desktop analog, since the
 * desktop app runs its pass while open and there is no scheduler to
 * ask otherwise.
 *
 * Nothing runs unless Orrery is on and the person turned the pipe on
 * for the active ship; then it is the same pass the app runs, once, with the model
 * released afterwards.
 */
class OrreryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? TalonApplication ?: return Result.success()
        val session = app.sessionStore.active() ?: return Result.success()
        if (app.db.orreryAccounts().get(session.ship) == null) return Result.success()
        // Orrery turned off (Settings > Orrery), on any device: no pass.
        if (app.aiSettings.state.value.savedProfile?.orrery != true) return Result.success()
        // The pass talks to the ship and to OpenRouter and waits on
        // both. Dispatchers.Default has one thread per core and the
        // screens' own list building shares it, so a pass run there
        // took the pool down with it while the app was open.
        val scope = CoroutineScope(SupervisorJob() + io.nisfeb.talon.util.ioDispatcher)
        val repo = OrreryRepo(
            app.session.http, scope, app.db, io.nisfeb.talon.ui.platformLabel, app.searchEmbedderClient,
            cloud = CloudTriage(
                io.nisfeb.talon.orrery.frontierReadsMessages(app.aiSettings),
            ) { app.aiSettings.state.value },
            book = { app.repo.bookContacts.value },
            standDown = io.nisfeb.talon.orrery.StandDown(app.uiSettings.orreryStandDown, app.uiSettings::setOrreryStandDown),
            decide = io.nisfeb.talon.orrery.DecideControl(app.uiSettings.orreryDecide, app.uiSettings::setOrreryDecide),
            location = io.nisfeb.talon.ui.AndroidLocationControl,
        )
        try {
            repo.pass(session.shipUrl, session.ship)
        } catch (t: Throwable) {
            Log.w(TAG, "pass failed: ${t.message}")
        } finally {
            scope.cancel()
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "OrreryWorker"
        private const val UNIQUE_NAME = "talon-orrery"

        /** Idempotent, from app startup; KEEP so restarts do not push the next run. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<OrreryWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresCharging(true)
                        .setRequiresDeviceIdle(true)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
