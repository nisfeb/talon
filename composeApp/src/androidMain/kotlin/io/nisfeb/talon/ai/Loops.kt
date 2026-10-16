package io.nisfeb.talon.ai

import kotlinx.coroutines.flow.first

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
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Android facade for user Loops — scheduling + headless execution,
 * An always-on (process-lifetime) singleton;
 * the ship-scoped db/repo/embedder are resolved lazily through the get*
 * lambdas so a ship switch (which rebuilds them) is picked up on the next
 * run rather than captured stale.
 *
 * One AlarmManager wake-up is armed at the earliest next-fire across all
 * enabled loops; on fire the receiver calls [runDueNow], which runs every
 * loop that's due and re-arms. Inexact (setAndAllowWhileIdle) like the
 * Loops are deferrable, which avoids the SCHEDULE_EXACT_ALARM
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
            withTimeoutOrNull(RUN_BUDGET_MS) { withRunner { it.runDue() } }
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
                withRunner { it.runLoop(loop) }
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
        if (sessionStore.activeShip() == null || !aiSettings.state.value.loopsCanRun()) {
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
    private val agentClient by lazy { AgentClient(io.nisfeb.talon.ai.AiFeature.Assistant) { aiSettings.state.value.forFeature(io.nisfeb.talon.ai.AiFeature.Assistant) } }
    // A dictation's own model where it has one, else the assistant's (AiProfile.resolve).
    private val dictationClient by lazy { AgentClient(io.nisfeb.talon.ai.AiFeature.Dictation) { aiSettings.state.value.forFeature(io.nisfeb.talon.ai.AiFeature.Dictation) } }
    private val braveClient by lazy { BraveSearchClient { aiSettings.state.value } }
    private val urlFetcher by lazy { UrlFetcher { aiSettings.state.value } }

    /**
     * A runner with the mail, calendar, task and event tools, over
     * connections made for this run and closed after it. A loop fires from
     * an alarm, where the app's own mail and calendar, built by the UI, may
     * not exist. On a scope of the run's own, ended with it.
     */
    private suspend fun <T> withActions(block: suspend (AssistantActions) -> T): T = withRunScope(scope) { run ->
        val url = sessionStore.active()?.shipUrl
        val http = runCatching { getSession().http }.getOrNull()
        val mail = if (url != null && http != null) io.nisfeb.talon.mail.MailRepo(http, run).also { it.attach(url, poll = false) } else null
        val calendar = if (url != null && http != null) io.nisfeb.talon.calendar.CalendarRepo(http, run).also { it.attach(url, poll = false) } else null
        try {
            // The calendar tools read its calendars, tasks and window; have them first.
            calendar?.refresh()
            val db = getDb()
            val contacts = runCatching {
                io.nisfeb.talon.ui.ContactMap(
                    contacts = db.contacts().stream().first(),
                    clubs = db.clubs().stream().first(),
                    groups = db.groups().streamGroups().first(),
                    channelGroups = db.groups().streamChannelGroups().first(),
                )
            }.getOrElse { io.nisfeb.talon.ui.ContactMap() }
            val actions = AssistantActions(
                db = db, contacts = { contacts }, mail = mail, calendar = calendar,
                zone = { io.nisfeb.talon.ui.screens.zoneFor(calendar?.zone?.value) },
                send = { whom, text -> getRepo().send(whom, text) },
            )
            block(actions)
        } finally {
            mail?.detach()
            calendar?.detach()
        }
    }

    private suspend fun <T> withRunner(block: suspend (LoopRunner) -> T): T =
        withActions { block(runner(it)) }

    /**
     * Begin a dictation's run before anything is said: the ship's channel,
     * mail and the calendar attached, the calendar read, the contacts
     * loaded. Started when listening starts, so it is ready, or nearly,
     * by the time the words are: on the phone this setup took six
     * seconds after them. [DictationPrep.close] it when the dictation is
     * over, run or not.
     */
    fun prepareDictation(): DictationPrep = DictationPrep()

    /**
     * One dictation's mail, calendar and contacts, on a scope of its own.
     * Nothing here waits: the calendar read runs on, and only the
     * calendar's tools wait for it ([waitingFor]), so a note for orrery
     * never does.
     */
    inner class DictationPrep internal constructor() {
        private val run = CoroutineScope(scope.coroutineContext + kotlinx.coroutines.SupervisorJob(scope.coroutineContext[kotlinx.coroutines.Job]))
        private val url = sessionStore.active()?.shipUrl
        private val http = runCatching { getSession().http }.getOrNull()
        private val mail = if (url != null && http != null) io.nisfeb.talon.mail.MailRepo(http, run).also { it.attach(url, poll = false) } else null
        private val calendar = if (url != null && http != null) io.nisfeb.talon.calendar.CalendarRepo(http, run).also { it.attach(url, poll = false) } else null
        private val started = run.async { runCatching { getRepo().start(getSession()) } }
        private val read = run.async {
            val t0 = System.currentTimeMillis()
            // What the tools read and no more: shares, sync state and tags were half the requests.
            runCatching { calendar?.refresh(rarely = false) }
            io.nisfeb.talon.util.Log.i("Dictation", "calendar read in ${System.currentTimeMillis() - t0} ms")
        }
        private val contacts = run.async {
            val db = getDb()
            runCatching {
                io.nisfeb.talon.ui.ContactMap(
                    contacts = db.contacts().stream().first(),
                    clubs = db.clubs().stream().first(),
                    groups = db.groups().streamGroups().first(),
                    channelGroups = db.groups().streamChannelGroups().first(),
                )
            }.getOrElse { io.nisfeb.talon.ui.ContactMap() }
        }

        /**
         * Act on [transcript], autonomously (writes need no confirmation,
         * see [DictationAgent]), and return the line for the notification.
         * The loop's mail, calendar and message tools plus orrery's where
         * Orrery is on, [DictationAgent.TOOLS] of them; while [locked], only
         * [DictationAgent.LOCKED_WRITES] among the writes. No write
         * coordinator: a dictation runs only on the device it was spoken
         * into. Throws when the run fails.
         */
        suspend fun dictate(transcript: String, locked: Boolean, earlier: List<DictationFlow.Earlier> = emptyList()): String {
            started.await()
            val cfg = aiSettings.state.value
            val known = contacts.await()
            val seen = Seen()
            val actions = AssistantActions(
                db = getDb(), contacts = { known }, mail = mail, calendar = calendar,
                zone = { io.nisfeb.talon.ui.screens.zoneFor(calendar?.zone?.value) },
                send = { whom, text -> getRepo().send(whom, text) },
                onEvents = seen::events, onTasks = seen::tasks, onPeople = seen::people, onMail = seen::mail,
            )
            val webOn = cfg.assistantOn()
            val tools = ToolCatalog.default(
                getRepo(), getDb(), getEmbedder(),
                braveSearch = braveClient.takeIf { webOn && cfg.braveApiKey.isNotBlank() },
                urlFetcher = urlFetcher.takeIf { webOn },
                onMessages = seen::messages,
            ) { known.displayName(it) } + actionTools(actions)
            return withOrrery(cfg) { orrery ->
                val offered = DictationAgent.toolsFor(tools + orrery?.let { orreryTools(it) }.orEmpty(), locked)
                    .waitingFor(DictationAgent.CALENDAR_TOOLS) { read.await() }
                // What a locked phone was allowed, readable in a release logcat.
                io.nisfeb.talon.util.Log.i("Dictation", "locked=$locked, ${offered.size} tools, calendar read ${if (read.isCompleted) "done" else "pending"}, writes: " + offered.filter { it.write }.joinToString { it.spec.name })
                DictationAgent(
                    // The time per call, as the assistant screen does:
                    // "tomorrow at three" needs today. The phone's zone
                    // until the calendar says its own.
                    completer = { sys, msgs, t -> dictationClient.completeWithTools(sys + "\n\n" + nowLine(actions.zone()), msgs, t) },
                    tools = offered,
                    systemPrompt = DictationAgent.prompt,
                ).run(transcript, earlier).also { card = seen.card(actions.zone(), known) }
            }
        }

        /** The events and tasks the last answer read, for its card; null before one. */
        @Volatile var card: DictationCard? = null
            private set

        /** What the calendar tools showed during one run. */
        private inner class Seen {
            private var events: Triple<List<io.nisfeb.talon.calendar.CalendarRow>, kotlinx.datetime.LocalDate, kotlinx.datetime.LocalDate>? = null
            private var tasks: List<io.nisfeb.talon.calendar.CalendarTask>? = null
            private val people = mutableListOf<PersonMatch>()
            private var messages: List<io.nisfeb.talon.data.MessageEntity>? = null
            private var mail: List<io.nisfeb.talon.mail.InboxEntry>? = null
            fun people(found: List<PersonMatch>) { people += found }
            fun messages(m: List<io.nisfeb.talon.data.MessageEntity>) { messages = m }
            fun mail(m: List<io.nisfeb.talon.mail.InboxEntry>) { mail = m }
            fun events(rows: List<io.nisfeb.talon.calendar.CalendarRow>, from: kotlinx.datetime.LocalDate, to: kotlinx.datetime.LocalDate) { events = Triple(rows, from, to) }
            fun tasks(t: List<io.nisfeb.talon.calendar.CalendarTask>) { tasks = t }
            fun card(zone: kotlinx.datetime.TimeZone, known: io.nisfeb.talon.ui.ContactMap): DictationCard {
                val now = System.currentTimeMillis()
                val twentyFour = android.text.format.DateFormat.is24HourFormat(context)
                val name = { ship: String -> known.displayName(ship) }
                return DictationCard(
                    days = events?.let { (r, f, t) -> DictationCards.days(r, f, t, zone, now, twentyFour) }.orEmpty(),
                    tasks = tasks?.let { DictationCards.tasks(it, zone, now) }.orEmpty(),
                    people = DictationCards.people(people),
                    messages = messages?.let { m ->
                        DictationCards.messages(m, name, { io.nisfeb.talon.urbit.StoryCache.textFor(it.id, it.contentJson) }, zone, now, twentyFour)
                    }.orEmpty(),
                    mail = mail?.let { DictationCards.mail(it, name, zone, now, twentyFour) }.orEmpty(),
                )
            }
        }

        /**
         * Answer a question, looking things up only: where Orrery is on,
         * everything that is not a question is orrery's ([DictationFlow]).
         */
        suspend fun ask(question: String, earlier: List<DictationFlow.Earlier> = emptyList()): String {
            val cfg = aiSettings.state.value
            if (!cfg.loopsCanRun()) return DictationRun.NO_MODEL_FOR_QUESTIONS
            started.await()
            val known = contacts.await()
            val seen = Seen()
            val actions = AssistantActions(
                db = getDb(), contacts = { known }, mail = mail, calendar = calendar,
                zone = { io.nisfeb.talon.ui.screens.zoneFor(calendar?.zone?.value) },
                onEvents = seen::events, onTasks = seen::tasks, onPeople = seen::people, onMail = seen::mail,
            )
            val tools = DictationAgent.readsFor(ToolCatalog.default(getRepo(), getDb(), getEmbedder(), onMessages = seen::messages) { known.displayName(it) } + actionTools(actions))
                .waitingFor(DictationAgent.CALENDAR_TOOLS) { read.await() }
            io.nisfeb.talon.util.Log.i("Dictation", "route=ask, ${tools.size} tools, calendar read ${if (read.isCompleted) "done" else "pending"}")
            return DictationAgent(
                completer = { sys, msgs, t -> dictationClient.completeWithTools(sys + "\n\n" + nowLine(actions.zone()), msgs, t) },
                tools = tools,
                systemPrompt = DictationAgent.askPrompt,
            ).run(question, earlier).also { card = seen.card(actions.zone(), known) }
        }

        /** Orrery for [block], attached headless, or null where Orrery is off. */
        suspend fun <T> orrery(block: suspend (io.nisfeb.talon.orrery.OrreryRepo?) -> T): T = withOrrery(aiSettings.state.value, block)

        fun close() {
            run.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            mail?.detach()
            calendar?.detach()
        }
    }

    /**
     * Orrery for one run, where it is on: attached headless, never
     * [io.nisfeb.talon.orrery.OrreryRepo.attach], which would take the
     * proposals of the one the app has attached when it let go.
     */
    private suspend fun <T> withOrrery(cfg: AiSettings.Config, block: suspend (io.nisfeb.talon.orrery.OrreryRepo?) -> T): T {
        val s = sessionStore.active()
        if (!cfg.orreryOn() || s == null) return block(null)
        val repo = io.nisfeb.talon.orrery.OrreryRepo(getSession().http, scope, getDb(), io.nisfeb.talon.ui.platformLabel, getEmbedder())
        return repo.headless(s.shipUrl, s.ship) { block(repo) }
    }

    private fun runner(actions: AssistantActions): LoopRunner {
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
            tools = tools + actionTools(actions),
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
