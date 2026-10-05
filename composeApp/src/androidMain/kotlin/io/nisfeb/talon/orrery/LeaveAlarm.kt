package io.nisfeb.talon.orrery

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.nisfeb.talon.Notifications
import io.nisfeb.talon.TalonApplication
import io.nisfeb.talon.ai.orreryOn
import io.nisfeb.talon.util.Log
import io.nisfeb.talon.util.createAppHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * The phone's alarm for orrery's time to leave (see [LeavePlan]): set a
 * minute after the ship's push is due, so it rings only when the push
 * did not come. The push, arriving through [io.nisfeb.talon.notify.ShipPushes],
 * calls [done] and the alarm stands down.
 *
 * Android-only: AlarmManager. iOS port pending: a local notification at
 * the same time, cancelled by the push. Desktop has no analog: it is not
 * with the owner when they leave.
 *
 * The plan is read every [POLL_MIN] minutes, on each location fix and
 * when the alarm fires, under the active ship's orrery key.
 * ponytail: a 30-minute poll; the ship moves leave-by 30, 15 and 5
 * minutes before the alert, which the poll can miss, so the alarm reads
 * the plan again when it fires and moves itself rather than ring early.
 */
object LeaveAlarm {
    private const val PREFS = "talon_orrery_leave"
    private const val WORK_POLL = "talon-orrery-leave-poll"
    private const val WORK_NOW = "talon-orrery-leave-now"
    private const val KEY_DONE = "done"
    private const val POLL_MIN = 30L
    internal const val TAG = "LeaveAlarm"
    private val keyClient by lazy { createAppHttpClient() }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** From every start with Orrery on; KEEP, so a start does not push the next read back. */
    fun schedule(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            WORK_POLL, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<LeaveWorker>(POLL_MIN, TimeUnit.MINUTES).setConstraints(online()).build(),
        )
    }

    /** Read the plan now, after a move: where the owner is changes when to leave. */
    fun refresh(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            WORK_NOW, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<LeaveWorker>().setConstraints(online()).build(),
        )
    }

    private fun online() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** The plan for the active ship, or null when there is none to keep an alarm for. */
    internal suspend fun fetch(app: TalonApplication): kotlin.Result<LeavePlan?> = runCatching {
        if (!app.aiSettings.state.value.orreryOn()) return@runCatching null
        val session = app.sessionStore.active() ?: return@runCatching null
        val token = app.db.orreryAccounts().get(session.ship)?.token ?: return@runCatching null
        readLeavePlan(OrreryApi(app.session.http, keyClient, session.shipUrl), token)
    }

    /** Keep an alarm for [plan], unless its occurrence is done or its time is behind. */
    fun set(ctx: Context, plan: LeavePlan, nowMs: Long = System.currentTimeMillis()) {
        if (isDone(ctx, plan.key)) return
        val at = leaveAlarmAtMs(plan)
        if (at <= nowMs) return
        prefs(ctx).edit()
            .putString("key", plan.key).putString("name", plan.name).putLong("leave", plan.leaveByMs)
            .putLong("alert", plan.alertAtMs).putInt("minutes", plan.minutes)
            .apply()
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = intent(ctx)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            // Not allowed exact: Doze may hold it some minutes.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
        Log.i(TAG, "alarm at ${kotlin.time.Instant.fromEpochMilliseconds(at)} for ${plan.key}")
    }

    fun clear(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java)?.cancel(intent(ctx))
        prefs(ctx).edit().remove("key").remove("name").remove("leave").remove("alert").remove("minutes").apply()
    }

    internal fun stored(ctx: Context): LeavePlan? {
        val p = prefs(ctx)
        return LeavePlan(
            key = p.getString("key", null) ?: return null,
            name = p.getString("name", null).orEmpty(),
            leaveByMs = p.getLong("leave", 0), alertAtMs = p.getLong("alert", 0), minutes = p.getInt("minutes", 0),
        )
    }

    /** [key]'s alert was given, by the ship's push or by this alarm: it is not set again. */
    fun done(ctx: Context, key: String) {
        val kept = (prefs(ctx).getString(KEY_DONE, null)?.split('\n').orEmpty() + key).distinct().takeLast(20)
        prefs(ctx).edit().putString(KEY_DONE, kept.joinToString("\n")).apply()
        if (stored(ctx)?.key == key) clear(ctx)
    }

    private fun isDone(ctx: Context, key: String) = prefs(ctx).getString(KEY_DONE, null)?.split('\n')?.contains(key) == true

    private fun intent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, REQ, Intent(ctx, LeaveAlarmReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    const val ACTION = "io.nisfeb.talon.action.LEAVE_ALARM"
    private const val REQ = 7702
}

/** Reads the plan and keeps the alarm to it. */
class LeaveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? TalonApplication ?: return Result.success()
        LeaveAlarm.fetch(app).fold(
            onSuccess = { plan ->
                if (plan == null) {
                    LeaveAlarm.clear(app)
                    Trips.disarm(app)
                } else {
                    Trips.arm(app, plan)
                    LeaveAlarm.set(app, plan)
                }
            },
            // Unreachable: the alarm already set stays; the next read tries again.
            onFailure = { Log.i(LeaveAlarm.TAG, "plan not read: ${it.message}") },
        )
        return Result.success()
    }
}

/**
 * The push did not come in time. Read the plan once more, briefly: moved
 * later, the alarm moves; gone, it is dropped; the same, or no answer
 * (no network is likely why the push did not come), it rings.
 *
 * Android-only: AlarmManager, see [LeaveAlarm].
 */
class LeaveAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LeaveAlarm.ACTION) return
        val app = context.applicationContext as? TalonApplication ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val stored = LeaveAlarm.stored(app) ?: return@launch
                val now = System.currentTimeMillis()
                val got = withTimeoutOrNull(10_000) { LeaveAlarm.fetch(app) }
                when (val d = leaveDecision(stored, got?.getOrNull(), answered = got?.isSuccess == true, nowMs = now)) {
                    is LeaveDecision.Ring -> {
                        Notifications.showLeave(app, d.plan, leaveTitle(d.plan, now), leaveBody(d.plan, kotlinx.datetime.TimeZone.currentSystemDefault()))
                        LeaveAlarm.done(app, d.plan.key)
                    }
                    is LeaveDecision.Move -> LeaveAlarm.set(app, d.plan, now)
                    LeaveDecision.Drop -> LeaveAlarm.clear(app)
                }
            } catch (t: Throwable) {
                Log.w(LeaveAlarm.TAG, "alarm failed", t)
            } finally {
                runCatching { pending.finish() }
            }
        }
    }
}
