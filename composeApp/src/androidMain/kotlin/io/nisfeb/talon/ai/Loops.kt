package io.nisfeb.talon.ai

import kotlinx.coroutines.flow.first
import io.nisfeb.talon.calendar.dueDate
import kotlinx.datetime.toLocalDateTime

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
 * enabled loops; on fire the receiver hands off to a worker that calls
 * [runDueNow], which runs every loop that's due and re-arms. Inexact
 * (setAndAllowWhileIdle): loops are deferrable, which avoids the
 * SCHEDULE_EXACT_ALARM permission.
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

    /** Run every due loop now, then re-arm. Called by the alarm's
     *  [io.nisfeb.talon.LoopWorker], which WorkManager keeps alive for as
     *  long as it takes. Capped so a slow agent can't hold the job open
     *  indefinitely; a loop the cap cuts short says so in its result. */
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
            withTimeoutOrNull(FIRE_BUDGET_MS) { withRunner { it.runDue(perLoopMs = RUN_BUDGET_MS) } }
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

    /** Allow, from the Loops screen: the held call, done as the loop's tool would ([LoopRunner.allow]). */
    fun allowHeld(holdId: Long) {
        scope.launch {
            runCatching {
                if (sessionStore.activeShip() == null) return@launch
                withRunner { it.allow(holdId) }
            }.onFailure { if (it is CancellationException) throw it }
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

    /** No ship cookie: lattice takes a request that carries one for the owner's, whatever key it shows. */
    private val bareHttp by lazy { io.nisfeb.talon.util.createAppHttpClient() }

    /** Place search for a dictation, where the assistant is on and the vendor offers search. */
    private fun placesClient(): BraveSearchClient? =
        aiSettings.state.value.takeIf { it.assistantOn() && it.braveSearch() != null }?.let { braveClient }
    private val urlFetcher by lazy { UrlFetcher { aiSettings.state.value } }

    /**
     * Maps or the dialer for a dictation's open_directions and call_place,
     * from the application context. Allowed from the background only while
     * the dictation's card is on screen; on a locked phone the app waits
     * behind the lock. With the card gone (hidden, or a retry from the
     * result notification) Android drops the start without a word, so it
     * is not tried and the tool says it could not open. No user action:
     * maps coming up is not the owner leaving the card, which hangs Archie
     * up ([io.nisfeb.talon.QuickDictationActivity.onUserLeaveHint]).
     */
    private fun openOnScreen(uri: String): Boolean {
        if (!io.nisfeb.talon.QuickDictation.shown.value) return false
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            )
        }.onFailure { io.nisfeb.talon.util.Log.w("Dictation", "could not open $uri: ${it.message}") }.isSuccess
    }

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
        /**
         * The owner's lattice, read while they talk: this install's key
         * (minted on first use), the index and the tools ([LatticeKeys]).
         * None where the ship has no Lattice.
         */
        private val memory = run.async {
            val s = sessionStore.active()
            val owner = http
            if (s == null || owner == null) LatticeMind.NONE
            else io.nisfeb.talon.util.runSuspendCatching {
                LatticeKeys(getDb().latticeKeys(), s.ship, LatticeApi(owner, bareHttp, s.shipUrl), io.nisfeb.talon.ui.platformLabel).mind()
            }.getOrDefault(LatticeMind.NONE)
        }

        /** [memory] as far as it has got: a slow ship's lattice is not waited for past a spoken question's wait. */
        private suspend fun recalled(): LatticeMind =
            kotlinx.coroutines.withTimeoutOrNull(LatticeMind.SPOKEN_WAIT_MS) { memory.await() } ?: LatticeMind.NONE

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
         * Act on [transcript], autonomously (writes need no confirmation
         * but a send, which waits for [send], see [DictationAgent]), and
         * return the line for the notification.
         * The loop's mail, calendar and message tools plus orrery's where
         * Orrery is on, [DictationAgent.TOOLS] of them; while [locked], only
         * [DictationAgent.LOCKED_WRITES] among the writes. While [lockedNow],
         * a read of messages or mail waits for [unlock]. No write
         * coordinator: a dictation runs only on the device it was spoken
         * into. Throws when the run fails.
         */
        suspend fun dictate(
            transcript: String,
            locked: Boolean,
            earlier: List<DictationFlow.Earlier>,
            send: suspend (CardProposal) -> Boolean?,
            lockedNow: () -> Boolean,
            unlock: suspend () -> Boolean,
        ): String {
            started.await()
            val cfg = aiSettings.state.value
            val known = contacts.await()
            val seen = CardSeen()
            val actions = AssistantActions(
                db = getDb(), contacts = { known }, mail = mail, calendar = calendar,
                zone = { io.nisfeb.talon.ui.screens.zoneFor(calendar?.zone?.value) },
                send = { whom, text -> getRepo().send(whom, text) },
                onEvents = seen::events, onTasks = seen::tasks, onPeople = seen::people, onMail = seen::mail,
                locked = { locked },
            )
            val webOn = cfg.assistantOn()
            // The vendor's search, not an own key: since Armillary-only an
            // own Brave key is always blank, and this gate had taken web
            // search away from every run.
            val searchOn = webOn && cfg.braveSearch() != null
            val tools = ToolCatalog.default(
                getRepo(), getDb(), getEmbedder(),
                braveSearch = braveClient.takeIf { searchOn },
                urlFetcher = urlFetcher.takeIf { webOn },
                places = braveClient.takeIf { searchOn },
                open = ::openOnScreen,
                dials = true,
                onMessages = seen::messages,
                conversationLabel = known::conversationLabel,
            ) { known.displayName(it) } + actionTools(actions)
            // Locked: memory's reads wait for the fingerprint, and nothing of
            // it is put before whoever is speaking.
            val mind = recalled().let { if (locked) it.locked() else it }
            return withOrrery(cfg) { orrery ->
                val offered = DictationAgent.toolsFor(tools + orrery?.let { orreryTools(it) }.orEmpty() + mind.tools, locked)
                    .waitingFor(DictationAgent.CALENDAR_TOOLS) { read.await() }
                // What a locked phone was allowed, readable in a release logcat.
                io.nisfeb.talon.util.Log.i("Dictation", "locked=$locked, ${offered.size} tools, calendar read ${if (read.isCompleted) "done" else "pending"}, writes: " + offered.filter { it.write }.joinToString { it.spec.name })
                DictationAgent(
                    // The time per call, as the assistant screen does:
                    // "tomorrow at three" needs today. The phone's clock,
                    // where the owner is, beside the calendar's zone.
                    completer = { sys, msgs, t -> dictationClient.completeWithTools(sys + "\n\n" + nowHere(actions.zone()), msgs, t) },
                    tools = offered,
                    systemPrompt = DictationAgent.prompt + mind.prompt.let { if (it.isBlank()) "" else "\n\n$it" },
                    ownerSends = send,
                    name = known::conversationLabel,
                    locked = lockedNow,
                    unlock = unlock,
                    ask = { mind.ask(it, LatticeMind.SPOKEN_WAIT_MS) },
                ).run(transcript, earlier).also { card = cardOf(seen, actions.zone(), known) }
            }
        }

        /** The events and tasks the last answer read, for its card; null before one. */
        @Volatile var card: DictationCard? = null
            private set

        /** A card of what [seen] saw, as the phone's clock says times. */
        private fun cardOf(seen: CardSeen, zone: kotlinx.datetime.TimeZone, known: io.nisfeb.talon.ui.ContactMap): DictationCard =
            seen.card(zone, { known.displayName(it) }, android.text.format.DateFormat.is24HourFormat(context), System.currentTimeMillis())

        private fun zone() = io.nisfeb.talon.ui.screens.zoneFor(calendar?.zone?.value)

        private fun today(): kotlinx.datetime.LocalDate =
            kotlin.time.Instant.fromEpochMilliseconds(System.currentTimeMillis()).toLocalDateTime(zone()).date

        /** What Archie opens with ([Briefing.day]), once the calendar is read, a while at most. */
        suspend fun day(decider: io.nisfeb.talon.orrery.Decider?): Briefing.Day {
            val known = contacts.await()
            return Briefing.day(
                getDb(), sessionStore.active()?.ship.orEmpty(), mail, calendar, decider, ::zone,
                android.text.format.DateFormat.is24HourFormat(context), { known.displayName(it) }, CardSeen(),
                calendarRead = { read.await() },
            )
        }

        /** What Archie's turns read, for each turn's card. */
        private val talkSeen = CardSeen()

        /**
         * Archie's conversation ([VoiceConversation]): a dictation's tools,
         * while [locked] only its locked writes, orrery's where it is on, and
         * orrery's proposals put to the owner by [decide] before they go ahead.
         * [locked] is read at each turn: the phone can lock mid-conversation.
         * While it is, a read of messages or mail waits for [unlock].
         */
        suspend fun conversation(
            locked: () -> Boolean,
            orrery: io.nisfeb.talon.orrery.OrreryRepo?,
            decide: suspend (String, List<io.nisfeb.talon.orrery.OrreryAction>) -> Boolean?,
            unlock: suspend () -> Boolean,
        ): VoiceConversation {
            started.await()
            val known = contacts.await()
            val actions = AssistantActions(
                db = getDb(), contacts = { known }, mail = mail, calendar = calendar,
                zone = { zone() },
                send = { whom, text -> getRepo().send(whom, text) },
                onEvents = talkSeen::events, onTasks = talkSeen::tasks, onPeople = talkSeen::people, onMail = talkSeen::mail,
                locked = locked,
            )
            val tools = ToolCatalog.default(getRepo(), getDb(), getEmbedder(), braveSearch = placesClient(), places = placesClient(), open = ::openOnScreen, dials = true, onMessages = talkSeen::messages, conversationLabel = known::conversationLabel) { known.displayName(it) } + actionTools(actions)
            // Every dictation tool; the conversation drops the locked ones turn by turn.
            val mind = recalled()
            val offered = DictationAgent.toolsFor(tools + orrery?.let { orreryTools(it) }.orEmpty() + mind.tools, locked = false)
                .waitingFor(DictationAgent.CALENDAR_TOOLS) { read.await() }
            return VoiceConversation(
                completer = { sys, msgs, t -> dictationClient.completeWithTools(sys + "\n\n" + nowHere(zone()), msgs, t) },
                tools = offered,
                tell = orrery?.let { o ->
                    { text: String ->
                        DictationFlow.tell(
                            text,
                            instruct = { o.instruct(it, apply = false).getOrThrow() },
                            decide = decide,
                            answer = { id, ok -> o.setAction(id, if (ok) "approved" else "dismissed").getOrThrow() },
                        )
                    }
                },
                brief = { orrery?.briefLast()?.getOrThrow()?.let { Briefing.todays(it, today()) } },
                locked = locked,
                unlock = unlock,
                memory = mind,
            )
        }

        /** What the last of Archie's turns read, for its card; forgotten for the next. */
        suspend fun turnCard(): DictationCard = cardOf(talkSeen, zone(), contacts.await()).also { talkSeen.reset() }

        /** Today's brief from [orrery], for the opener; null for none today or no answer. */
        suspend fun brief(orrery: io.nisfeb.talon.orrery.OrreryRepo?): io.nisfeb.talon.orrery.Brief? =
            orrery?.briefLast()?.getOrNull()?.let { Briefing.todays(it, today()) }

        /**
         * One exchange on the card into Assistant history ([io.nisfeb.talon.data.VoiceHistory]),
         * after [conversation], and pushed to the owner's other devices: the
         * conversation it went into. A failure keeps the card going.
         */
        suspend fun remember(
            conversation: io.nisfeb.talon.data.AssistantConversationEntity?,
            title: String,
            question: String,
            answer: String,
        ): io.nisfeb.talon.data.AssistantConversationEntity? = try {
            io.nisfeb.talon.data.VoiceHistory.record(getDb(), conversation, title, question, answer, System.currentTimeMillis()) { c, t ->
                getRepo().settingsSync?.pushAssistantTurn(c, t)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            io.nisfeb.talon.util.Log.w("Dictation", "could not keep the exchange in Assistant history: ${e.message}")
            conversation
        }

        /** The time, for the opener's digest. */
        fun now(): String = nowHere(zone())

        /**
         * Answer a question, looking things up only: where Orrery is on,
         * everything that is not a question is orrery's ([DictationFlow]).
         * [orrery] is the one the flow attached, read for what it holds.
         * While [locked], a read of messages or mail waits for [unlock].
         */
        suspend fun ask(
            question: String,
            earlier: List<DictationFlow.Earlier>,
            orrery: io.nisfeb.talon.orrery.OrreryRepo?,
            locked: () -> Boolean,
            unlock: suspend () -> Boolean,
        ): String {
            val cfg = aiSettings.state.value
            if (!cfg.loopsCanRun()) return DictationRun.NO_MODEL_FOR_QUESTIONS
            started.await()
            val known = contacts.await()
            val seen = CardSeen()
            val actions = AssistantActions(
                db = getDb(), contacts = { known }, mail = mail, calendar = calendar,
                zone = { io.nisfeb.talon.ui.screens.zoneFor(calendar?.zone?.value) },
                onEvents = seen::events, onTasks = seen::tasks, onPeople = seen::people, onMail = seen::mail,
            )
            val mind = recalled().let { if (locked()) it.locked() else it }
            val tools = DictationAgent.readsFor(ToolCatalog.default(getRepo(), getDb(), getEmbedder(), braveSearch = placesClient(), places = placesClient(), open = ::openOnScreen, dials = true, onMessages = seen::messages, conversationLabel = known::conversationLabel) { known.displayName(it) } + actionTools(actions) + mind.tools, orrery?.asTap())
                .waitingFor(DictationAgent.CALENDAR_TOOLS) { read.await() }
            io.nisfeb.talon.util.Log.i("Dictation", "route=ask, ${tools.size} tools, calendar read ${if (read.isCompleted) "done" else "pending"}")
            return DictationAgent(
                completer = { sys, msgs, t -> dictationClient.completeWithTools(sys + "\n\n" + nowHere(actions.zone()), msgs, t) },
                tools = tools,
                systemPrompt = DictationAgent.askPrompt + mind.prompt.let { if (it.isBlank()) "" else "\n\n$it" },
                locked = locked,
                unlock = unlock,
                ask = { mind.ask(it, LatticeMind.SPOKEN_WAIT_MS) },
            ).run(question, earlier).also { card = cardOf(seen, actions.zone(), known) }
        }

        /**
         * A plain question about what is on, answered from the calendar read
         * while the owner talked, with no model: the card's days and tasks
         * and a line to say ([CalendarQuestion]). Null where it is not one,
         * the [decider] says it is not or there is none, or the calendar
         * did not answer, and the model takes it.
         */
        suspend fun calendarAnswer(text: String, earlier: List<DictationFlow.Earlier>, decider: io.nisfeb.talon.orrery.Decider?): String? {
            val cal = calendar ?: return null
            if (decider == null) return null
            val seen = CardSeen()
            return CalendarQuestion.fromCalendar(
                cal, decider, text, earlier, android.text.format.DateFormat.is24HourFormat(context), seen,
                ready = { read.await() },
            ) { io.nisfeb.talon.util.Log.i("Dictation", it) }?.also { card = cardOf(seen, zone(), contacts.await()) }
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
        val searchOn = webOn && cfg.braveSearch() != null
        val tools = ToolCatalog.default(
            getRepo(), db, getEmbedder(),
            braveSearch = braveClient.takeIf { searchOn },
            urlFetcher = urlFetcher.takeIf { webOn },
            places = braveClient.takeIf { searchOn },
        ) { it }
        return LoopRunner(
            loops = db.loops(),
            runs = db.loopRuns(),
            holds = db.loopHolds(),
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
        // Every loop due in one fire, each with its own budget above, under
        // WorkManager's ten minutes for a worker, with the connect wait.
        private val FIRE_BUDGET_MS = TimeUnit.MINUTES.toMillis(8)
        private val WAKE_LOCK_MS = TimeUnit.SECONDS.toMillis(150)
        // Bounded wait for the %settings channel on a cold headless fire
        // with a write loop due — long enough for an SSE connect on mobile
        // data, short enough to leave the run budget intact.
        private val CONNECT_WAIT_MS = TimeUnit.SECONDS.toMillis(15)
    }
}
