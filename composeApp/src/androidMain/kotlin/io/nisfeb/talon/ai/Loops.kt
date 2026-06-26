package io.nisfeb.talon.ai

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import io.nisfeb.talon.LoopAlarmReceiver
import io.nisfeb.talon.Notifications
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.urbit.SessionStore
import io.nisfeb.talon.urbit.TlonChatRepo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Android facade for user Loops — scheduling + headless execution,
 * modeled on [DailyDigest]. An always-on (process-lifetime) singleton;
 * the ship-scoped db/repo/embedder are resolved lazily through the get*
 * lambdas so a ship switch (which rebuilds them) is picked up on the next
 * run rather than captured stale.
 *
 * One AlarmManager wake-up is armed at the earliest next-fire across all
 * enabled loops; on fire the receiver calls [runDueNow], which runs every
 * loop that's due and re-arms. Inexact (setAndAllowWhileIdle) like the
 * digest — loops are deferrable, which avoids the SCHEDULE_EXACT_ALARM
 * permission.
 *
 * Android-only: no desktop analog — depends on AlarmManager / BootReceiver
 * (CLAUDE.md §6). Desktop's equivalent is a while-open ticker, gated off
 * via isLoopsSupported until it lands.
 */
class Loops(
    private val context: Context,
    private val sessionStore: SessionStore,
    private val getDb: () -> AppDatabase,
    private val getRepo: () -> TlonChatRepo,
    private val getEmbedder: () -> SearchEmbedderClient?,
    private val aiSettings: AiSettingsRepository,
    private val scope: CoroutineScope,
) : LoopScheduler {

    private val alarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** Re-arm the single wake-up at the earliest next-fire. Fire-and-
     *  forget (the DAO read is suspend, so it hops onto [scope]). Safe to
     *  call after any loop change, on key change, and after each run. */
    override fun reschedule() {
        scope.launch { runCatching { rearm() } }
    }

    /** Suspend re-arm, for callers that can await it — e.g. BootReceiver
     *  under goAsync(), so the alarm is set before a freshly-booted
     *  process can be reclaimed (fire-and-forget [reschedule] would race). */
    suspend fun rescheduleNow() = rearm()

    /** Run every due loop now, then re-arm. Called by the alarm receiver
     *  inside goAsync(). Capped so a slow agent can't hold the wake lock /
     *  goAsync budget open indefinitely.
     *
     *  ponytail: goAsync + one timeout is the Stage-1 ceiling. If loops
     *  routinely run long, or many come due at once, move execution to a
     *  foreground service (TalonSyncService-style) — a BroadcastReceiver
     *  isn't meant to hold the process for minutes. */
    suspend fun runDueNow() {
        try {
            if (sessionStore.activeShip() == null) return
            withTimeoutOrNull(RUN_BUDGET_MS) { runner().runDue() }
        } finally {
            runCatching { rearm() }
        }
    }

    /** Fire-and-forget single run for the Loops screen's "Run now". Re-arms
     *  after, since the run advances lastRunAt and the armed wake-up would
     *  otherwise still point at the pre-run (now-earlier) fire time. */
    fun runOneNow(loopId: Long) {
        scope.launch {
            runCatching {
                if (sessionStore.activeShip() == null) return@launch
                val loop = getDb().loops().get(loopId) ?: return@launch
                runner().runLoop(loop)
            }.onFailure { if (it is CancellationException) throw it }
            runCatching { rearm() }
        }
    }

    private suspend fun rearm() {
        val pi = buildPendingIntent()
        // Disarm when there's nothing to run: no ship, no AI key (loops
        // can't execute), or no enabled loops. Otherwise a keyless device
        // with an enabled loop would wake, do nothing, and (since lastRunAt
        // never advances) re-arm in the past — a wasteful no-progress loop.
        if (sessionStore.activeShip() == null || !aiSettings.state.value.hasKey()) {
            alarmManager.cancel(pi); return
        }
        val enabled = getDb().loops().enabled()
        if (enabled.isEmpty()) {
            alarmManager.cancel(pi); return
        }
        val fireMs = enabled.minOf {
            LoopSchedule.nextFireMs(it.lastRunAt, it.intervalMinutes)
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireMs, pi)
    }

    private fun runner(): LoopRunner {
        val db = getDb()
        // Full catalog (reads + writes); LoopRunner keeps write tools only
        // for loops with writesAuthorized set. displayName is the raw patp —
        // headless we have no live ContactMap to resolve nicknames.
        val tools = ToolCatalog.default(
            getRepo(), db, getEmbedder(),
            braveSearch = BraveSearchClient { aiSettings.state.value },
            urlFetcher = UrlFetcher { aiSettings.state.value },
        ) { it }
        val agentClient = AgentClient { aiSettings.state.value }
        return LoopRunner(
            loops = db.loops(),
            runs = db.loopRuns(),
            tools = tools,
            completer = { sys, msgs, t -> agentClient.completeWithTools(sys, msgs, t) },
            aiConfig = { aiSettings.state.value },
            // One device runs a scheduled write fire — the %settings lease.
            // Noop if this build has no sync channel (a write loop needs the
            // ship anyway, so an un-coordinated device can't double-write).
            coordinator = getRepo().settingsSync ?: LoopWriteCoordinator.Noop,
            // Tag the notification on the stable loop id (not the name).
            notify = { id, title, body ->
                Notifications.showLoop(context, id, title, body, System.currentTimeMillis())
            },
        )
    }

    private fun buildPendingIntent(): PendingIntent {
        val intent = Intent(context, LoopAlarmReceiver::class.java).apply {
            action = ACTION_LOOP_FIRE
        }
        return PendingIntent.getBroadcast(
            context, REQ_LOOPS, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun acquireWakeLock(tag: String): PowerManager.WakeLock {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "talon:$tag").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_MS)
        }
    }

    companion object {
        const val ACTION_LOOP_FIRE = "io.nisfeb.talon.action.LOOP_FIRE"
        private const val REQ_LOOPS = 7701
        // Headroom for one full agent round-trip (AgentClient's own call
        // timeout is 120s). A run cut by this budget still advances the
        // schedule (LoopRunner stamps lastRunAt up front), so the value is
        // a safety ceiling, not a correctness knob.
        private val RUN_BUDGET_MS = TimeUnit.SECONDS.toMillis(120)
        private val WAKE_LOCK_MS = TimeUnit.SECONDS.toMillis(150)
    }
}
