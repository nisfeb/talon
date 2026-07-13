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
 * (CLAUDE.md §6). Desktop runs the same [LoopRunner] on a while-open ticker
 * in App.kt (with LoopScheduler.Noop), so jobs there only fire while Talon
 * is running. Nothing to port unless desktop grows a background daemon.
 */
class Loops(
    private val context: Context,
    private val sessionStore: SessionStore,
    private val getDb: () -> AppDatabase,
    private val getRepo: () -> TlonChatRepo,
    private val getEmbedder: () -> SearchEmbedderClient?,
    private val aiSettings: AiSettingsRepository,
    private val scope: CoroutineScope,
    // Lazy: the app builds Loops before it builds the UrbitSession.
    private val getSession: () -> io.nisfeb.talon.urbit.UrbitSession,
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
            // Headless cold start: the alarm can fire with no UI ever
            // composed this process, so the repo session — and the %settings
            // channel the write-lease (and any ship write) needs — was never
            // started. start() is idempotent; when a write-authorized loop is
            // due, wait briefly for the channel so the fire can actually
            // coordinate + post instead of skipping every background wake.
            runCatching { getRepo().start(getSession()) }
            val t = System.currentTimeMillis()
            val zone = kotlinx.datetime.TimeZone.currentSystemDefault()
            val dueWrites = getDb().loops().enabled().any {
                it.writesAuthorized && LoopSchedule.isDue(t, it, zone)
            }
            val sync = getRepo().settingsSync
            if (dueWrites && sync != null && !sync.canCoordinate()) {
                withTimeoutOrNull(CONNECT_WAIT_MS) {
                    while (!sync.canCoordinate()) kotlinx.coroutines.delay(500)
                }
            }
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
        val zone = kotlinx.datetime.TimeZone.currentSystemDefault()
        val fireMs = enabled.minOf {
            LoopSchedule.nextFireMs(it, zone)
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireMs, pi)
    }

    // Each of these owns an OkHttpClient (thread + connection pool). They read
    // settings through a lambda, so one instance for the process lifetime
    // never goes stale on a key change — and every wake-up reuses the pool
    // instead of leaving a fresh one behind.
    private val agentClient by lazy { AgentClient { aiSettings.state.value } }
    private val braveClient by lazy { BraveSearchClient { aiSettings.state.value } }
    private val urlFetcher by lazy { UrlFetcher { aiSettings.state.value } }

    private fun runner(): LoopRunner {
        val db = getDb()
        // Full catalog (reads + writes); LoopRunner keeps write tools only
        // for loops with writesAuthorized set. displayName is the raw patp —
        // headless we have no live ContactMap to resolve nicknames.
        // Web access belongs to the assistant: with it off, both tools hard-
        // refuse every call. Gate their PRESENCE (as AssistantScreen does) so
        // a scheduled run never sees a tool it can only fail with.
        val cfg = aiSettings.state.value
        val webOn = cfg.assistantOn()
        val tools = ToolCatalog.default(
            getRepo(), db, getEmbedder(),
            braveSearch = braveClient.takeIf { webOn && cfg.braveApiKey.isNotBlank() },
            urlFetcher = urlFetcher.takeIf { webOn },
        ) { it }
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

    fun acquireWakeLock(tag: String): PowerManager.WakeLock =
        context.acquireTalonWakeLock(tag, WAKE_LOCK_MS)

    companion object {
        const val ACTION_LOOP_FIRE = "io.nisfeb.talon.action.LOOP_FIRE"
        private const val REQ_LOOPS = 7701
        // Headroom for one full agent round-trip (AgentClient's own call
        // timeout is 120s). A run cut by this budget still advances the
        // schedule (LoopRunner stamps lastRunAt up front), so the value is
        // a safety ceiling, not a correctness knob.
        private val RUN_BUDGET_MS = TimeUnit.SECONDS.toMillis(120)
        private val WAKE_LOCK_MS = TimeUnit.SECONDS.toMillis(150)
        // Bounded wait for the %settings channel on a cold headless fire
        // with a write loop due — long enough for an SSE connect on mobile
        // data, short enough to leave the run budget intact.
        private val CONNECT_WAIT_MS = TimeUnit.SECONDS.toMillis(15)
    }
}
