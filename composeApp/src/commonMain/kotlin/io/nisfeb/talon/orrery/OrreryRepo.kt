package io.nisfeb.talon.orrery

import io.nisfeb.talon.ai.forFeature
import io.nisfeb.talon.ai.featureOn
import io.ktor.client.HttpClient
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.nisfeb.talon.calendar.CalendarApi
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.OrreryAccountEntity
import io.nisfeb.talon.data.OrreryNoticedEntity
import io.nisfeb.talon.ui.isLocalTriageSupported
import io.nisfeb.talon.urbit.StoryCache
import io.nisfeb.talon.mail.AuspexApi
import io.nisfeb.talon.ui.shipHandle
import io.nisfeb.talon.ui.shipHandleLong
import io.nisfeb.talon.util.Log
import io.nisfeb.talon.util.createAppHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.datetime.Instant
import io.nisfeb.talon.util.nowMs
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import io.nisfeb.talon.ui.parseIsoUtc
import io.nisfeb.talon.urbit.asText
import io.nisfeb.talon.ai.hasModelFor

/**
 * The structural pipe into orrery: what Talon knows for certain about
 * contacts, who wrote and when, and the calendar, pushed to the ship
 * as observations under a key this install minted for itself.
 *
 * Off until the user turns it on. Turning it on mints the key and
 * starts a walk from thirty days back; turning it off revokes the key
 * on the ship and forgets it here. While on, every source is pushed
 * again every ten minutes from its cursor. Observation ids are content
 * hashes, so a cursor that lags costs a resend the ship answers
 * `existing`, never a duplicate.
 *
 * Text never goes up. An observation's source is a pointer Talon can
 * open, and its value is a date, a name, a place or a time.
 */
class OrreryRepo(
    private val http: HttpClient,
    private val scope: CoroutineScope,
    private val db: AppDatabase,
    private val platform: String,
    /** The device's embedder, where it has one; the pattern gate needs it. */
    private val embedder: io.nisfeb.talon.ai.SearchEmbedderClient? = null,
    /** The cloud opt-in, where the shell offers one. */
    val cloud: CloudTriage? = null,
    /**
     * The ships in the person's own contacts book. Bodies are made for
     * these and for nobody else the ship has merely heard of: the
     * contacts table holds every peer ever seen, which is thousands.
     */
    private val book: () -> Set<String> = { emptySet() },
    /** On a phone, whether to leave the reading to a computer that has read lately. */
    val standDown: StandDown? = null,
    /**
     * The cross-device lease in the ship's %settings. Without it there
     * is no brief: two installs must never both pay for one.
     */
    private val claim: (suspend (key: String, staleMs: Long, settleMs: Long) -> Boolean)? = null,
    /** The decision model's switches: the gate before the reader and the status check after it. */
    val decide: DecideControl? = null,
    /** An Urbit DM, for approved message actions on the chat channel; without it Talon sends none. */
    private val sendDm: (suspend (whom: String, text: String) -> Unit)? = null,
    /** The key's client, which carries no cookie. A test hands in its own ship. */
    bareClient: HttpClient? = null,
) {
    private var cloudModel: LocalModel? = null

    /** The last gate built, and the tray examples it was built from. */
    private var gateBuilt: Pair<Pair<List<String>, List<String>>, PatternGate>? = null

    // Writes under the key ride a client with no cookie: with both on
    // one request the ship would take the cookie and write as the owner.
    private val bare: HttpClient by lazy { bareClient ?: createAppHttpClient() }

    private val _availability = MutableStateFlow(OrreryAvailability.UNKNOWN)
    val availability: StateFlow<OrreryAvailability> = _availability.asStateFlow()
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _lastPushMs = MutableStateFlow<Long?>(null)
    val lastPushMs: StateFlow<Long?> = _lastPushMs.asStateFlow()
    private val _pushing = MutableStateFlow(false)
    val pushing: StateFlow<Boolean> = _pushing.asStateFlow()
    /** The rung the triage reads with, or the best it could, and where it stands. */
    private val _model = MutableStateFlow<Pair<String, RungStatus>?>(null)
    val model: StateFlow<Pair<String, RungStatus>?> = _model.asStateFlow()
    private val _download = MutableStateFlow<Float?>(null)
    val download: StateFlow<Float?> = _download.asStateFlow()
    /** The analyst's open actions, as of the last pass. */
    private val _actions = MutableStateFlow<List<OrreryAction>>(emptyList())
    val actions: StateFlow<List<OrreryAction>> = _actions.asStateFlow()
    /** True while this phone is leaving the reading to a computer. */
    private val _yielding = MutableStateFlow(false)
    val yielding: StateFlow<Boolean> = _yielding.asStateFlow()

    private var api: OrreryApi? = null
    // Coroutines only touch this, so a mutex is the whole of the guard
    // (commonMain has no synchronized: iOS is native).
    private val pending = mutableListOf<Facts>()
    private val calls = mutableListOf<((String, String?) -> String) -> Facts>()
    private val transcripts = mutableListOf<Pair<String, List<Spoken>>>()
    private val pendingLock = Mutex()
    // The pass and an answer can both reach the mirror; one at a time,
    // or both see no todo and each make one.
    private val mirrorLock = Mutex()
    private var shipUrl: String? = null
    private var ship: String? = null
    private var loop: Job? = null
    private var watching: Job? = null
    private var beacon: Job? = null

    /**
     * New proposals to raise and answered ones to take back, for the
     * host to deliver as notifications. The decision is [diffActionNotifications].
     */
    var onActions: ((raise: List<ActionNotification>, clear: Set<String>) -> Unit)? = null
    private var seenProposals: Set<String>? = null

    private val _generator = MutableStateFlow<GeneratorRun?>(null)
    /** What the ship's generator last did, read with the open list. */
    val generator: StateFlow<GeneratorRun?> = _generator.asStateFlow()
    private val _generatorSettings = MutableStateFlow<GeneratorSettings?>(null)
    /** The ship's generator settings, as the owner reads them; null until read, or where there is no generator. */
    val generatorSettings: StateFlow<GeneratorSettings?> = _generatorSettings.asStateFlow()

    suspend fun loadGenerator() {
        val a = api ?: return
        a.generatorSettings()?.let { _generatorSettings.value = it }
        a.generatorLast()?.let { _generator.value = it }
    }

    /**
     * Point the ship's generator: on or off, and at a base, model and
     * key where given. The key goes to the ship, which keeps it and
     * never gives it back. Anything not given is left as the ship has it.
     */
    suspend fun setGenerator(enabled: Boolean, url: String? = null, model: String? = null, key: String? = null): Result<Unit> = runCatching {
        val a = attached()
        a.setGenerator(enabled, url, model, key)
        a.generatorSettings()?.let { _generatorSettings.value = it }
    }

    private val _decideToday = MutableStateFlow<Pair<String, DecideDay>?>(null)
    /** Today's tally of the decision model on this install, as the log has it. */
    val decideToday: StateFlow<Pair<String, DecideDay>?> = _decideToday.asStateFlow()

    /** Today's tally as kept, for the settings screen before a pass has added to it. */
    suspend fun loadDecideToday() {
        val s = ship ?: return
        val day = localDay(now())
        _decideToday.value = day to keptDay(s, day)
    }

    /** The owner's calendar day at [ms], which is what a day's tally is kept under. */
    private fun localDay(ms: Long): String = kotlinx.datetime.Instant.fromEpochMilliseconds(ms)
        .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault()).date.toString()

    /** The tally kept for [day], or an empty one. */
    private suspend fun keptDay(s: String, day: String): DecideDay =
        db.orrerySent().get(s, "decide:$day")?.value
            ?.let { runCatching { Json.decodeFromString(DecideDay.serializer(), it) }.getOrNull() } ?: DecideDay()

    /** The open list as the ship just said it, and what that means for notifications. */
    private fun published(list: List<OrreryAction>) {
        _actions.value = list
        val news = diffActionNotifications(list, seenProposals)
        seenProposals = news.seen
        if (news.raise.isNotEmpty() || news.clear.isNotEmpty()) onActions?.invoke(news.raise, news.clear)
    }

    fun attach(shipUrl: String, ship: String) {
        if (this.shipUrl == shipUrl && this.ship == ship) return
        detach()
        this.shipUrl = shipUrl
        this.ship = ship
        api = OrreryApi(http, bare, shipUrl)
        current = this
        scope.launch {
            probe()
            _enabled.value = db.orreryAccounts().get(ship) != null
            if (_enabled.value) startLoop()
            if (_availability.value == OrreryAvailability.PRESENT) {
                refreshActions()
                // Orrery's beacon moves once for every write that changed
                // something: a new proposal, or an answer given anywhere.
                // It is what makes a notification prompt and an answered
                // action leave at once. The slow read below is only a net
                // for a stream that went quiet without closing.
                watchBeacon(shipUrl)
            }
            runCatching { refreshModel() }
        }
        watching = scope.launch {
            while (isActive) {
                delay(ACTIONS_EVERY_MS)
                if (_availability.value == OrreryAvailability.PRESENT) refreshWaiting()
            }
        }
    }

    /**
     * One stream of orrery's change beacon while attached, and a read of
     * what is waiting whenever the revision moves. A stream that ends is
     * opened again after a pause that grows, with jitter; a quiet one is
     * left alone, since quiet is not dead.
     */
    private fun watchBeacon(shipUrl: String) {
        beacon?.cancel()
        beacon = scope.launch {
            var pause = 3_000L
            var last: String? = null
            while (isActive) {
                runCatching {
                    http.prepareGet(shipUrl.trimEnd('/') + BEACON_PATH) {
                        header(io.ktor.http.HttpHeaders.Accept, "text/event-stream")
                    }.execute { resp ->
                        if (!resp.status.isSuccess()) error("the beacon answered ${resp.status.value}")
                        pause = 3_000L
                        val body = resp.bodyAsChannel()
                        val reader = BeaconReader()
                        while (isActive) {
                            val line = body.readUTF8Line() ?: break
                            val rev = reader.feed(line) ?: continue
                            // The first revision on a connection is where
                            // things stand: a read only if it moved while
                            // nobody was listening.
                            if (last != null && rev != last) refreshWaiting()
                            last = rev
                        }
                    }
                }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; Log.i(TAG, "beacon: ${it.message}") }
                delay(pause + kotlin.random.Random.nextLong(0, 2_000))
                pause = (pause * 2).coerceAtMost(5 * 60_000L)
            }
        }
    }

    // ---- what the assistant is given ----------------------------------
    //
    // Orrery's own routes, thinly. The assistant is told what orrery's
    // documents mean by orrery, not by a wrapper here: a setting this
    // app has never heard of is one the assistant can still write, and
    // that is the point of keeping these general.

    /** One settings document, as the ship serves it; credentials masked. */
    suspend fun readSettings(name: String): Result<String> = runCatching { attached().settingsDoc(name) }

    /** Merge into a settings document. The ship keeps what is left blank. */
    suspend fun writeSettings(name: String, body: JsonObject): Result<String> = runCatching {
        val a = attached()
        val said = a.setSettingsDoc(name, body)
        // The screens hold the generator's settings; a write from
        // anywhere else has to reach them or the card shows the old ones.
        if (name == "generator") runCatching { a.generatorSettings() }.getOrNull()?.let { _generatorSettings.value = it }
        said
    }

    /** Register a settings document with the service it names; the ship's answer. */
    suspend fun register(name: String): Result<String> = runCatching { attached().register(name) }

    /** What the outside service holds for a registered document. */
    suspend fun readRegistration(name: String): Result<String> = runCatching { attached().registration(name) }

    /** The state view under this install's key: bodies and what is known of them. */
    suspend fun readState(): Result<JsonObject> = runCatching { attached().stateJson(key()) }

    /** Ask the ship which body a name means, before writing about it. */
    suspend fun resolveBody(q: String): Result<List<ResolvedBody>> = runCatching { attached().resolve(q, key()) }

    /** One body's timeline: what was said about it, when, and by whom. */
    suspend fun bodyTimeline(id: String): Result<List<KnownObs>> = runCatching { attached().observationsOf(id, key()) }

    /** One observe batch under this install's key. */
    suspend fun observeNow(batch: JsonObject): Result<ObserveAnswer> = runCatching { attached().observe(batch, key()) }

    private fun attached(): OrreryApi = api ?: error("Not attached to a ship.")

    private suspend fun keyToken(): String? = ship?.let { db.orreryAccounts().get(it)?.token }

    private suspend fun key(): String = keyToken() ?: error("This install has no orrery key yet.")

    /**
     * What is open on the ship, read again: one small request, no
     * calendar. What opening Actions asks for; the mirror, which reads
     * every action ever filed and the whole calendar, runs on attach,
     * after an answer and in the pipe's pass, not on every look.
     */
    suspend fun refreshWaiting() {
        val a = api ?: return
        if (ship == null) return
        runCatching { a.actions(keyToken()) }
            .onSuccess { published(it) }
            .onFailure { Log.i(TAG, "actions skipped: ${it.message}") }
        // A pass files proposals and moves the beacon: its record comes with them.
        a.generatorLast()?.let { _generator.value = it }
    }

    fun detach() {
        if (current === this) current = null
        stopPipe()
        watching?.cancel()
        watching = null
        beacon?.cancel()
        beacon = null
        seenProposals = null
        api = null
        shipUrl = null
        ship = null
        _availability.value = OrreryAvailability.UNKNOWN
        _error.value = null
        scopeChecked = false
    }

    /** The pipe's loop stops. */
    private fun stopPipe() {
        loop?.cancel()
        loop = null
        _enabled.value = false
    }

    /**
     * The pipe is off for good: turned off, or its key refused. The
     * location switch lives under the pipe, so it goes off the screen
     * with it, and is turned off with it: left on, the phone kept
     * waking for moves with nowhere to send them and no way to say
     * stop.
     *
     * Only here, not on detach. Detaching is a restart, a ship switch,
     * an Activity going away: turning the saved switch off there put it
     * off on every cold start and every time the app was swiped away,
     * which is exactly when hearing moves with the app closed matters.
     */
    private fun turnOff() {
        stopPipe()
        io.nisfeb.talon.ui.stopLocationSharing()
    }

    suspend fun probe() {
        val a = api ?: return
        runCatching { a.probe() }
            .onSuccess { _availability.value = it; _error.value = null }
            .onFailure { _availability.value = OrreryAvailability.UNKNOWN; _error.value = it.message }
    }

    /** Mint this install's key and start the walk. */
    suspend fun enable(): Result<Unit> = runCatching {
        val a = attached()
        val s = ship ?: error("Not attached to a ship.")
        // Everything the ship has; the lists are only a fallback.
        val full = runCatching { a.schema() }.getOrNull()
        val key = a.mint(
            "Talon on $platform", by(),
            full?.let(::schemaKinds) ?: OrreryApi.KINDS,
            full?.let(::schemaActions) ?: OrreryApi.ACTIONS,
        )
        scopeChecked = full != null
        val start = now() - BACKFILL_MS
        db.orreryAccounts().upsert(OrreryAccountEntity(s, key.id, key.token, start, start, 0))
        _enabled.value = true
        _error.value = null
        startLoop()
    }

    /** Revoke the key on the ship and forget it here. */
    suspend fun disable(): Result<Unit> = runCatching {
        val s = ship ?: return@runCatching
        // The one step that can fail goes first. It used to go after the
        // loop was stopped and the records wiped, so an unreachable ship
        // left the switch on, the pipe dead and every record gone, and
        // the next pass would have made twins of what it had merged.
        db.orreryAccounts().get(s)?.let { row ->
            // A key the ship has already dropped answers 404; that is
            // the state we want, not a failure to report.
            runCatching { api?.revoke(row.clientId) }
                .onFailure { if (it !is OrreryError.Refused || it.status != 404) throw it }
        }
        turnOff()
        db.orrerySent().clear(s)
        db.orreryAccounts().delete(s)
        _error.value = null
    }

    private fun startLoop() {
        loop?.cancel()
        loop = scope.launch {
            while (isActive) {
                // A pass that throws is a pass lost, never the pipe: the
                // loop ended there once, and nothing said so.
                try {
                    push()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "pass failed before it began", e)
                    _error.value = e.message ?: e::class.simpleName
                }
                // Wake for seven in the owner's zone, so the brief is not
                // up to a pass late.
                val wait = briefZone?.let { Brief.untilNext(now(), it, briefGrace) + 1_000 } ?: PUSH_EVERY_MS
                delay(minOf(PUSH_EVERY_MS, wait))
            }
        }
    }

    /**
     * One pass and nothing kept, for a background worker: attach without
     * a loop or a probe, push, then let the model and the ship go, so a
     * runtime of a gigabyte does not stay resident after the work.
     */
    suspend fun pass(shipUrl: String, ship: String) {
        this.shipUrl = shipUrl
        this.ship = ship
        api = OrreryApi(http, bare, shipUrl)
        try {
            push()
        } finally {
            runCatching { LocalModels.reset() }
            cloudModel?.close()
            cloudModel = null
            api = null
            this.ship = null
            this.shipUrl = null
        }
    }

    /**
     * The daily brief, and the owner's replies to it. Replies are read
     * every pass. The brief goes once a day, from seven until noon in
     * the owner's zone, and only whole: when the ship, the calendar or
     * the model cannot answer, nothing is sent and the log says why.
     * The state is read fresh for it, never remembered.
     */
    private suspend fun brief(
        a: OrreryApi,
        token: String,
        s: String,
        url: String,
        nowMs: Long,
        /** The state as the pass read it, before its own writes. */
        seen: JsonObject?,
        /** The mail this pass listed, newer than the cursor. */
        fresh: List<io.nisfeb.talon.mail.InboxEntry>,
        /** Every action, as the pass read them. */
        actions: List<OrreryAction>?,
    ): List<io.nisfeb.talon.mail.InboxEntry> {
        val mail = AuspexApi(http, url)
        // The zone off the state the pass already read: a brief that is
        // not due costs the ship nothing at all.
        val state = seen ?: a.stateJson(token)
        val zone = Brief.zone(state)
        briefZone = zone
        val unfinished = runCatching { answerReplies(a, token, s, mail, state, zone, nowMs, fresh) }
            .onFailure { Log.w(TAG, "replies to the brief skipped: ${it.message}") }
            .getOrDefault(fresh.filter { it.count > 1 && Brief.dayOf(it.subject) != null })
        if (cloud?.config?.invoke()?.featureOn(io.nisfeb.talon.ai.AiFeature.OrreryBrief, before = true) == false) return unfinished
        val day = Brief.dueDay(nowMs, zone, briefGrace) ?: return unfinished
        val sent = db.orrerySent()
        if (sent.get(s, "brief:$day") != null) return unfinished
        // Another install may have sent today's; the ship's mail says so.
        // ponytail: two computers waking at seven can still both send;
        // the check again below narrows it to the seconds of one send.
        suspend fun sentElsewhere(listed: List<io.nisfeb.talon.mail.InboxEntry>? = null): Boolean {
            val threads = listed ?: mail.inbox(io.nisfeb.talon.mail.MailView.ALL, limit = 50).threads
            val there = threads.any { Brief.dayOf(it.subject) == day }
            if (there) sent.put(io.nisfeb.talon.data.OrrerySentEntity(s, "brief:$day", "", nowMs))
            return there
        }
        // A brief another install sent today is newer than the cursor,
        // so the mail this pass already listed answers the first check.
        if (sentElsewhere(fresh)) return unfinished
        val frontier = cloud?.config?.invoke()?.takeIf { it.hasModelFor(io.nisfeb.talon.ai.AiFeature.OrreryBrief) }?.forFeature(io.nisfeb.talon.ai.AiFeature.OrreryBrief)
            ?: run { Log.i(TAG, "brief not sent: no frontier model is set under AI"); return unfinished }
        // One install writes the brief, and it holds the day's lease
        // before anything costs money. A holder that goes quiet for
        // twenty minutes, longer than a model call, can be taken over.
        val lease = claim ?: run { Log.i(TAG, "brief not sent: no way to coordinate with other installs here"); return unfinished }
        if (!lease(BRIEF_LEASE, Brief.LEASE_STALE_MS, Brief.LEASE_SETTLE_MS)) {
            Log.i(TAG, "brief left to the install holding today's lease")
            return unfinished
        }
        // The one read worth making: the brief speaks for the state as
        // it stands after this pass wrote to it.
        val fresh = runCatching { a.stateJson(token) }.getOrDefault(state)
        val cal = CalendarApi(http, url)
        val from = day.atStartOfDayIn(zone).toEpochMilliseconds()
        val events = cal.window(from, from + 26 * 3_600_000L).rows
        val todos = cal.tasks()
        val all = actions ?: a.actions(token, status = "all")
        val today = Brief.today(day, zone, events, todos, fresh)
        val (waiting, tags) = Brief.waiting(all, zone, Brief.names(fresh))
        val decided = all.filter { it.status in setOf("done", "dismissed", "failed") }.sortedBy { it.id }
        // What the last brief suggested, kept beside its tags: today's
        // says what has changed rather than the same thing again.
        val saidYesterday = sent.under(s, SAID).maxByOrNull { it.key }?.value.orEmpty()
        val suggestions = io.nisfeb.talon.ai.AiClient(io.nisfeb.talon.ai.AiFeature.OrreryBrief) { frontier }.complete(
            Brief.SYSTEM,
            Brief.statePrompt(fresh, decided, isoUtc(nowMs), zone, today, waiting, saidYesterday),
            maxOutputTokens = 4000,
            timeoutMs = 180_000,
        )
        // Again: the model took its time, and another install may have finished first.
        if (sentElsewhere()) return unfinished
        mail.send(listOf(s), Brief.subject(day), Brief.render(day, today, waiting, suggestions))
        // The tags go with the day: only the install that sent a brief
        // knows which action each one names, so only it answers replies.
        val tagJson = kotlinx.serialization.json.buildJsonObject { tags.forEach { (t, id) -> put(t, kotlinx.serialization.json.JsonPrimitive(id)) } }
        sent.put(io.nisfeb.talon.data.OrrerySentEntity(s, "brief:$day", tagJson.toString(), nowMs))
        // And what it said, which only the next brief reads: one row, so
        // the older ones go rather than a row a day forever.
        sent.under(s, SAID).forEach { if (it.key != "$SAID$day") sent.forget(s, it.key) }
        sent.put(io.nisfeb.talon.data.OrrerySentEntity(s, "$SAID$day", suggestions.trim(), nowMs))
        Log.i(TAG, "brief for $day sent, ${tags.size} waiting")
        return unfinished
    }

    /**
     * Each reply to a brief this install sent, once, by its message id,
     * read by the frontier model that wrote the brief: it moves the
     * tagged actions, files what the owner asked for, and writes the
     * rest as facts in the owner's word. Every piece is held to the ship
     * before it is written. A reply with no model to read it waits.
     */
    private suspend fun answerReplies(
        a: OrreryApi,
        token: String,
        s: String,
        mail: AuspexApi,
        state: JsonObject,
        zone: kotlinx.datetime.TimeZone,
        nowMs: Long,
        /** The mail this pass listed: a reply is newer than the cursor, so it is in here. */
        fresh: List<io.nisfeb.talon.mail.InboxEntry>,
    ): List<io.nisfeb.talon.mail.InboxEntry> {
        val sent = db.orrerySent()
        val threads = fresh.filter { it.count > 1 && Brief.dayOf(it.subject) != null }
        // What could not be finished holds the mail cursor back, so the
        // next pass lists it again rather than losing the owner's words.
        val unfinished = mutableListOf<io.nisfeb.talon.mail.InboxEntry>()
        for (entry in threads) {
            val tagsRaw = sent.get(s, "brief:${Brief.dayOf(entry.subject)}")?.value?.takeIf { it.isNotBlank() } ?: continue
            val tags = Json.parseToJsonElement(tagsRaw).jsonObject.mapValues { it.value.jsonPrimitive.content }
            val thread = runCatching { mail.thread(entry.id) }.getOrElse { unfinished += entry; continue } ?: continue
            val brief = Brief.briefOf(thread, s) ?: continue
            val handled = sent.some(s, thread.messages.map { "reply:${it.id}" }).map { it.key.removePrefix("reply:") }.toSet()
            for (reply in Brief.pendingReplies(thread, s, handled)) {
                val words = Brief.ownWords(reply.body, brief.body)
                if (words.isNotBlank()) {
                    val read = runCatching { answer(a, token, state, zone, nowMs, reply, words, tags) }
                    if (read.isFailure) {
                        Log.w(TAG, "reply ${reply.id} not read: ${read.exceptionOrNull()?.message}")
                        unfinished += entry
                        continue
                    }
                }
                // Only once all of it is written: a reply that failed
                // halfway is read again, and every write is one the ship
                // answers as existing or refuses as already done.
                sent.put(io.nisfeb.talon.data.OrrerySentEntity(s, "reply:${reply.id}", "", nowMs))
            }
        }
        return unfinished.distinctBy { it.id }
    }

    private suspend fun answer(
        a: OrreryApi,
        token: String,
        state: JsonObject,
        zone: kotlinx.datetime.TimeZone,
        nowMs: Long,
        reply: io.nisfeb.talon.mail.MailMessage,
        words: String,
        tags: Map<String, String>,
    ) {
        val frontier = cloud?.config?.invoke()?.takeIf { it.hasModelFor(io.nisfeb.talon.ai.AiFeature.OrreryBrief) }?.forFeature(io.nisfeb.talon.ai.AiFeature.OrreryBrief)
            ?: error("no frontier model is set under AI")
        val at = reply.sent.takeIf { it > 0 } ?: nowMs
        val byId = a.actions(token, status = "all").associateBy { it.id }
        val tagged = tags.mapNotNull { (t, id) -> byId[id]?.let { t to it } }.toMap()
        val view = a.viewOf(state)
        // ponytail: an answer that is not JSON throws and the reply is
        // asked again next pass; a model that keeps failing keeps costing.
        val answer = Brief.parseAnswer(
            io.nisfeb.talon.ai.AiClient(io.nisfeb.talon.ai.AiFeature.OrreryBrief) { frontier }.complete(
                Brief.REPLY_SYSTEM,
                Brief.analystPrompt(state, view.attrs, view.notes, reply.id, at, words, tagged, nowMs, zone),
                maxOutputTokens = 8000,
                timeoutMs = 180_000,
            ),
        ) ?: error("the answer to reply ${reply.id} was not JSON")
        // Rule 2: a body the answer would make is asked for first.
        val known = view.bodies.map { it.id }.toSet()
        val resolved = mutableMapOf<String, String>()
        for (b in (answer["bodies"] as? kotlinx.serialization.json.JsonArray).orEmpty()) {
            val o = b as? JsonObject ?: continue
            val id = o["id"]?.jsonPrimitive?.content?.lowercase() ?: continue
            if (id in known) continue
            val q = o["name"]?.jsonPrimitive?.content ?: id.substringAfter('/')
            runCatching { a.resolve(q, token) }.getOrDefault(emptyList())
                .firstOrNull { it.isExact && it.kind == id.substringBefore('/') }
                ?.let { resolved[id] = it.id }
        }
        val facts = Brief.replyFacts(answer, known, view.attrs, resolved, reply.id, at)
        val moves = Brief.movesOf(answer, tags, known)
        val asked = Brief.replyActions(answer, state["schema"] as? JsonObject, known) { Log.i(TAG, "reply ${reply.id}: dropped $it") }
        for (d in moves) move(a, token, byId[d.actionId] ?: continue, d)
        for (body in asked) {
            runCatching { a.act(body, token) }.onFailure { Log.i(TAG, "reply ${reply.id}: an action was refused: ${it.message}") }
        }
        for (batch in batches(facts)) {
            a.observe(batch, token).refused.forEach { Log.w(TAG, "reply ${reply.id}: refused ${it.error}") }
        }
        Log.i(TAG, "reply ${reply.id}: ${moves.size} moves, ${asked.size} actions, ${facts.observations.size} facts")
    }

    /** One move from a reply: a new due or subject replaces the action, then the status moves. */
    private suspend fun move(a: OrreryApi, token: String, old: OrreryAction, d: Brief.Direction) {
        // The owner's reason, or none: a note on a dismissal is read by the
        // generator as the owner's taste, so Talon never writes its own.
        val note = d.reason.orEmpty()
        var id = old.id
        var status = old.status
        if (d.dueMs != null || d.about != null) {
            // Replaced, not refused: no reason, since the owner gave none
            // and still wants the thing.
            runCatching { a.transition(token, old.id, "dismissed", "") }
                .onFailure { Log.i(TAG, "${old.id} not dismissed: ${it.message}") }
            val (newId, newStatus) = a.act(Brief.replacement(old, d.dueMs, d.about), token)
            id = newId
            status = newStatus
        }
        for (step in Brief.steps(status, d.status ?: return)) {
            runCatching { a.transition(token, id, step, note) }
                .onFailure { Log.i(TAG, "$id not moved to $step: ${it.message}") }
        }
    }

    /**
     * The executor's one job, on a pass. The mirror that used to live
     * here is gone: as of orrery 34 the ship keeps the todo list, puts
     * approved calendar actions on the calendar, and sends Telegram and
     * mail itself, on its own executor fiber. Two mirrors over one list
     * place twice and tick twice, so Talon keeps none of it.
     */
    private suspend fun runExecutor(
        a: OrreryApi,
        token: String?,
        read: List<OrreryAction>? = null,
    ): Map<String, String> = mirrorLock.withLock {
        val settled = mutableMapOf<String, String>()
        if (token == null) return@withLock settled
        val actions = read ?: a.actions(token, status = "all")
        if (actions.isEmpty()) return@withLock settled
        runCatching { sendApproved(a, token, actions, settled) }
            .onFailure { Log.i(TAG, "messages skipped: ${it.message}") }
        settled
    }

    /**
     * The executor, rule 14: an approved message action whose `via` is
     * `chat` is claimed, confirmed as ours, sent as an Urbit DM to the
     * ship the person's own attribute gives, and reported with a note
     * the owner reads. That is the whole of it. Telegram and mail are
     * the ship's own, as of orrery 34, and a claim it holds answers
     * `claimed by ship`, which is left alone. What was sent is
     * remembered before it is reported, so a pass that dies between the
     * two reports it next time instead of sending it again.
     */
    private suspend fun sendApproved(
        a: OrreryApi,
        token: String,
        actions: List<OrreryAction>,
        /** What this pass moved on, for whoever reads the listing after it. */
        settled: MutableMap<String, String>,
    ) {
        val s = ship ?: return
        val dm = sendDm ?: return
        val out = actions.filter { it.status == "approved" || it.status == "claimed" }
            .mapNotNull { act -> act.messageToSend()?.takeIf { it.via in TALON_CHANNELS }?.let { act to it } }
        if (out.isEmpty()) return
        val state = a.stateJson(token)
        for ((act, m) in out) {
            val key = "sent:${act.id}"
            val was = db.orrerySent().get(s, key)
            if (was != null) {
                runCatching { a.transition(token, act.id, "done", was.value) }.onSuccess { settled[act.id] = "done" }
                continue
            }
            val why = runCatching { a.claim(token, act.id) }.getOrElse { it.message ?: "the claim was refused" }
            if (why != null) {
                Log.i(TAG, "message ${act.id} not ours: $why")
                continue
            }
            val address = addressOf(state, m.to, m.via)
            if (address == null) {
                a.transition(token, act.id, "failed", noAddress(state, m.to, m.via))
                settled[act.id] = "failed"
                continue
            }
            // The record goes in before the send. It used to go in after,
            // so a pass the phone stopped in between sent the message
            // again on the next one. A send that comes back failed takes
            // it out again, so the owner can approve it a second time.
            db.orrerySent().put(io.nisfeb.talon.data.OrrerySentEntity(s, key, UNCONFIRMED, now()))
            // One channel, and it is the only one Talon claims. Mail
            // goes out from the ship now, through auspex, to the ship
            // the person's own attribute gives; Telegram from the
            // ship's bot. Neither was ever Talon's to send twice.
            val sent = runCatching { dm(address, m.text); "sent as a DM to $address" }
            val note = sent.getOrNull()
            if (note == null) {
                db.orrerySent().forget(s, key)
                a.transition(token, act.id, "failed", "not sent: ${sent.exceptionOrNull()?.message ?: "no answer"}")
                settled[act.id] = "failed"
                continue
            }
            db.orrerySent().put(io.nisfeb.talon.data.OrrerySentEntity(s, key, note, now()))
            a.transition(token, act.id, "done", note)
            settled[act.id] = "done"
        }
    }

    /**
     * What the ship was told about occurrences the calendar has since
     * moved or called off, taken back, and those occurrences forgotten
     * so the new times are written as new. Rows of this event only, and
     * only inside the window, which is the one stretch of time this
     * install can see the truth of.
     */
    private suspend fun retractMoved(
        a: OrreryApi,
        token: String,
        bodyId: String,
        subject: CalendarSubject,
        seen: Map<String, String>,
        s: String,
        nowMs: Long,
    ): Set<String> {
        val stale = staleOccurrences(subject, seen, nowMs - BACKFILL_MS, nowMs + AHEAD_MS)
        if (stale.isEmpty()) return emptySet()
        val rows = runCatching { a.observationsOf(bodyId, token) }
            .onFailure { Log.i(TAG, "timeline skipped: ${it.message}") }
            .getOrNull() ?: return emptySet()
        val here = subject.occurrences.flatMap { listOf(it.l, it.r) }.toSet()
        val gone = stale.flatMap { it.second }.toSet() - here
        val src = "${subject.cal}/${subject.uid}"
        for (o in rows) {
            if (o.sourceId != src || !o.stands || o.atMs !in gone) continue
            runCatching { a.retract(o.id, "the calendar no longer has this event at this time", token) }
                .onFailure { Log.i(TAG, "retract skipped: ${it.message}") }
        }
        val keys = stale.map { it.first }.toSet()
        keys.forEach { db.orrerySent().forget(s, it) }
        return keys
    }

    /**
     * The mail newer than [cursor], a page at a time from the newest.
     * A quiet inbox costs one small listing rather than two hundred
     * threads: every request into a grubbery app is about a second of
     * the ship's single thread, and they queue behind each other. With
     * no cursor (a first pass, or the record cleared) the whole page
     * this pass may take is read.
     */
    internal suspend fun mailSince(mail: AuspexApi, cursor: Long): List<io.nisfeb.talon.mail.InboxEntry> {
        // The inbox, as the pipe has always read: the whole of the mail
        // would hand it the owner's own sent copies as facts.
        val view = io.nisfeb.talon.mail.MailView.INBOX
        if (cursor <= 0L) return mail.inbox(view, limit = MAIL_PER_PASS).threads.filter { it.last > cursor }
        val out = mutableListOf<io.nisfeb.talon.mail.InboxEntry>()
        var offset = 0
        while (offset < MAIL_PER_PASS) {
            val page = mail.inbox(view, offset = offset, limit = MAIL_PAGE).threads
            if (page.isEmpty()) break
            val fresh = page.filter { it.last > cursor }
            out += fresh
            // The listing is newest first, so a page holding anything
            // this install has already read is the last one worth asking for.
            if (fresh.size < page.size) break
            offset += page.size
        }
        return out
    }

    /**
     * One pass over every source from its cursor. Safe to call any
     * time, and safe to run again from nothing: the ship is asked what
     * it already has before anything is made, every occurrence and
     * message this install has handled is remembered, and a body is
     * created once or never. Replaying used to recreate whatever the
     * owner's consolidation had just merged away, which is how the
     * calendar's events came back as hollow twins.
     */
    suspend fun push() {
        val a = api ?: return
        val s = ship ?: return
        val url = shipUrl ?: return
        val row0 = db.orreryAccounts().get(s) ?: return
        ensureScope(s, row0)
        if (_pushing.value) return
        _pushing.value = true
        // One pass in the process at a time. The app's loop and the
        // background worker each hold a repo of their own over the same
        // rows and the same local model: run together, they wrote each
        // other's cursors back and whichever finished first closed the
        // model the other was still reading with. The row is read under
        // the lock, so the cursors this pass starts from are the ones
        // the pass before it left.
        var locked = false
        var queuedForRetry: List<Facts> = emptyList()
        var spokenForRetry: List<Pair<String, List<Spoken>>> = emptyList()
        // Put back what this pass took from the queues, for the next one.
        suspend fun requeue() = pendingLock.withLock {
            pending.addAll(0, queuedForRetry)
            transcripts.addAll(0, spokenForRetry)
        }
        try {
            // Inside the try, so a pass cancelled while it waits for the
            // lock still clears [_pushing]. Outside it, the flag stayed
            // set and every later pass returned at once: the pipe went
            // quiet until the process restarted.
            passLock.lock()
            locked = true
            val row = db.orreryAccounts().get(s) ?: return
            val nowMs = now()
            var facts = Facts()
            val queued = pendingLock.withLock { pending.toList().also { pending.clear() } }
            queued.forEach { facts += it }
            queuedForRetry = queued
            // A call's words, taken here rather than inside the triage, so
            // a pass that fails can put them back: nothing else keeps them.
            val spoken = pendingLock.withLock { transcripts.toList().also { transcripts.clear() } }
            spokenForRetry = spoken
            // What the ship has, which is what a client goes by. It is
            // never told what this install remembers.
            val raw = runCatching { a.stateJson(row.token) }.getOrNull()
            val view = raw?.let(a::viewOf)
            val sent = db.orrerySent()
            val record = mutableListOf<io.nisfeb.talon.data.OrrerySentEntity>()
            val forgets = mutableListOf<String>()
            fun remember(key: String, value: String = "") {
                record += io.nisfeb.talon.data.OrrerySentEntity(s, key, value, nowMs)
            }

            val book = book()
            val people = People(a, row.token, sent, s, view?.bodies.orEmpty())
            // Calls made since the last pass, their speakers resolved now.
            val called = pendingLock.withLock { calls.toList().also { calls.clear() } }.map { it(people::idFor) }
            called.forEach { facts += it }
            queuedForRetry = queuedForRetry + called
            // Read once: the table holds every peer ever seen, and the
            // triage below wants the same few.
            val known = db.contacts().all().filter { it.ship == s || it.ship in book }
            for (c in known) {
                val handle = shipHandle(c.ship)
                val id = people.idFor(c.ship, c.nickname ?: handle)
                val body = personBody(c, id, handle, shipHandleLong(c.ship))
                // A body the ship does not have is made. One it has is
                // taught the names it lacks and nothing else: an upsert
                // carrying aliases alone unions them and leaves the
                // ship's own name, so a new nickname arrives without
                // remaking a body the owner may have merged. What the
                // ship goes by is read from the ship, not remembered.
                facts += Facts(bodies = teachNames(body, people.goesBy(body.id)))
            }

            val ahead = db.messages().postsAfter(row.messagesCursor, s, MESSAGES_PER_PASS)
            // The cursor is the author's clock, so a post that syncs after
            // the cursor passed its time sits under it for good: the other
            // channel catching up just after the app opened. The newest
            // few under the cursor are walked again, and kept only where
            // no msg: record says a pass already read them. Not on the
            // first pass, which would reach back past the backfill, and
            // not while the phone leaves the reading to a computer, when
            // it writes no records to check against.
            // ponytail: a catch-up deeper than LATE_POSTS still loses the
            // rest; a cursor on insertion order (a column and a migration)
            // is the whole fix.
            val under = if (_yielding.value || row.calendarCursor == 0L) emptyList()
            else db.messages().postsBefore(row.messagesCursor + 1, s, LATE_POSTS)
            val readAlready = if (under.isEmpty()) emptySet()
            else sent.some(s, under.map { "msg:${it.whom}/${it.id}" }).mapTo(HashSet()) { it.key }
            val posts = under.filter { "msg:${it.whom}/${it.id}" !in readAlready }.reversed() + ahead
            // Contact from a DM is contact with you. In a channel it is only
            // worth recording when the author is already in your book.
            val direct = posts.filter { isDirect(it.whom) || it.author in book }
            facts += Facts(observations = direct.mapNotNull { m -> messageFacts(m, s, people.idFor(m.author, null)) })
            var messagesCursor = ahead.maxOfOrNull { it.sentMs } ?: row.messagesCursor

            // Mail and the calendar may be absent on this ship; a source
            // that is not there is skipped, not an error of the pipe.
            var mailCursor = row.mailCursor
            var freshMail: List<io.nisfeb.talon.mail.InboxEntry> = emptyList()
            val mailApi = AuspexApi(http, url)
            runCatching { mailSince(mailApi, row.mailCursor) }.onSuccess { fresh ->
                freshMail = fresh
                facts += Facts(
                    observations = freshMail.flatMap { e ->
                        mailFacts(e, s, nowMs) { ship -> people.idFor(ship, null) }
                    },
                )
                mailCursor = freshMail.maxOfOrNull { it.last } ?: mailCursor
            }.onFailure { Log.i(TAG, "mail skipped: ${it.message}") }
            val triaged = triage(a, row, posts, spoken, s, nowMs, url, freshMail, known, view) { key, value -> remember(key, value) }
            facts += triaged.facts

            val calApi = CalendarApi(http, url)
            // The whole listing, read once and shared: the vanished
            // check reads it, and so does the task mirror below.
            var allEvents: List<io.nisfeb.talon.calendar.CalendarTask>? = null
            suspend fun events(): List<io.nisfeb.talon.calendar.CalendarTask>? {
                allEvents?.let { return it }
                return runCatching { calApi.events() }.onFailure { Log.i(TAG, "events skipped: ${it.message}") }
                    .getOrNull()?.also { allEvents = it }
            }
            runCatching { calApi.window(nowMs - BACKFILL_MS, nowMs + AHEAD_MS) }.onSuccess { w ->
                // The calendars this ship keeps itself. An event on one
                // another ship shares is not ours to name an organizer
                // for. Asked for only where this pass writes something:
                // on a pass where nothing moved it is a request the ship
                // spends a second on for an answer nobody reads.
                var calendars: List<io.nisfeb.talon.calendar.CalendarInfo>? = null
                suspend fun calendarsNow(): List<io.nisfeb.talon.calendar.CalendarInfo> =
                    calendars ?: runCatching { calApi.calendars() }.getOrDefault(emptyList()).also { calendars = it }
                // Who the ship keeps, so a name in a title lands on the
                // person it already has.
                val cast = view?.let { EventPeople.of(it.bodies) } ?: EventPeople.NONE
                // What this install has written, read once for the pass.
                // A prefix read cannot use an index, and the table also
                // holds a row per message read, so one scan per event
                // was the cost of the whole loop.
                val written = sent.under(s, "cal:").associate { it.key to it.value }
                val occurrences = sent.under(s, "occ:").associate { it.key to it.value }
                val occByEvent = occurrences.entries.groupBy({ it.key.substringBeforeLast('/') }, { it.key to it.value })
                for (subject in calendarSubjects(w.rows)) {
                    // The body decided for this event, and the event as
                    // it was when that decision was made.
                    val mark = written[subject.key]?.takeIf { it.isNotBlank() }
                    val decided = mark?.substringBefore('|')
                    val digest = subject.digest
                    val seen = occByEvent["occ:${subject.cal}/${subject.uid}"].orEmpty().toMap()
                    // An occurrence the calendar no longer has at a time
                    // this install can still see: moved, or called off.
                    val dropped = if (decided == null) emptySet() else retractMoved(a, row.token, decided, subject, seen, s, nowMs)
                    // An occurrence the ship was told the schedule of,
                    // which has since happened: what was said in the
                    // future tense is said again in the past.
                    val due = seen.any { (key, record) ->
                        Occurrence.unsettled(record) && (Occurrence.endOf(record) ?: Long.MAX_VALUE) <= nowMs &&
                            key !in dropped
                    }
                    // A new time, place or description means what the
                    // ship was told no longer describes the event.
                    val changed = mark != null &&
                        (mark.substringAfter('|', "") != digest || dropped.isNotEmpty() || due)
                    // Ask the ship before making anything: by the calendar's
                    // own id first, which reconcile keeps as an alias of what
                    // it built, then by the title, which only a body that is
                    // plainly this occasion may answer.
                    val hits = if (decided != null) emptyList() else {
                        val byUid = runCatching { a.resolve(subject.uid, row.token) }.getOrDefault(emptyList())
                        val byTitle = if (byUid.any { it.isExact }) emptyList()
                        else runCatching { a.resolve(subject.title, row.token) }.getOrDefault(emptyList())
                        listOfNotNull(sameEvent(subject, byUid, byTitle) { id -> raw?.let { bodyTimes(it, id) } })
                    }
                    // Whether anything of this event is going up: a new
                    // body, a change, or an occurrence never written.
                    val writes = decided == null || changed ||
                        subject.occurrences.any { occurrenceKey(subject, it) !in seen.keys }
                    val ours = writes && subject.cal in calendarsNow().filter { it.kind == "local" }.map { it.id }
                    val write = calendarWrite(
                        subject, decided, hits, seen.keys - dropped, nowMs, changed,
                        ours, cast,
                    )
                    facts += write.facts
                    // A renamed event: the ship keeps the name it has and
                    // learns the new one as an alias, so whoever resolves
                    // by either finds the one body.
                    if (changed && decided != null && view != null) {
                        facts += Facts(
                            bodies = teachNames(
                                OBody(decided, name = null, aliases = listOf(subject.title, normalizeTitle(subject.title))),
                                people.goesBy(decided),
                                make = false,
                            ),
                        )
                    }
                    if (mark != "${write.bodyId}|$digest") remember(subject.key, "${write.bodyId}|$digest")
                    write.occurrences.forEach { remember(it.key, it.record) }
                }
                // An event this install wrote that the calendar no longer
                // keeps at all. Read from the full listing: the window
                // also loses an event moved past its edge.
                // ponytail: only what this install remembers writing; a
                // cleared record (pipe off and on) forgets what to cancel.
                // What this install wrote and the calendar no longer
                // keeps. Nothing written, nothing to lose: the listing
                // is only worth a request once there is a record. The
                // records are the ones read before the loop: what the
                // loop forgot or remembered was for events still here.
                if (written.isNotEmpty()) {
                    val all = events()
                    // An empty listing is likelier a hiccup than every
                    // event deleted at once, and a cancel is not undone.
                    val kept = all?.map { "cal:${it.cal}/${it.id}" }?.toSet()
                    // And the calendar list is only asked for once an
                    // event has actually gone: most passes, none has.
                    if (!all.isNullOrEmpty() && kept != null && written.keys.any { it !in kept }) {
                        val gone = vanishedEvents(
                            written = written,
                            occurrences = occurrences,
                            kept = kept,
                            calendars = calendarsNow().map { it.id }.toSet(),
                            nowMs = nowMs,
                        )
                        facts += gone.facts
                        forgets += gone.forget
                    }
                }
            }.onFailure { Log.i(TAG, "calendar skipped: ${it.message}") }

            // Only what the ship's schema lists for a kind it keeps: a row
            // on an attribute the owner has not named sits outside their
            // vocabulary, and the ship's readers never see it.
            view?.attrs?.takeIf { it.isNotEmpty() }?.let { attrs ->
                val (listed, not) = facts.observations.partition { o -> attrs[o.subject.substringBefore('/')]?.contains(o.attr) != false }
                if (not.isNotEmpty()) {
                    Log.i(TAG, "${not.size} rows not written, the schema lacks " + not.map { "${it.subject.substringBefore('/')}.${it.attr}" }.distinct().joinToString())
                    facts = facts.copy(observations = listed)
                }
            }
            var refused = 0
            var firstReason: String? = null
            for (batch in batches(facts)) {
                val answer = a.observe(batch, row.token)
                answer.refused.forEach {
                    refused++
                    if (firstReason == null) firstReason = it.error
                    Log.w(TAG, "refused: ${it.error}")
                }
            }
            // Rule 16: once the facts are on the ship, and only then,
            // ask for a pass now. The generator otherwise waits out the
            // owner's cooldown, so a breakdown at five past ten would
            // sit until eleven. The ship stores nothing about urgency
            // and counts this against the owner's own small daily cap,
            // so a "held" is an answer rather than a failure.
            triaged.urgentAbout?.let { about ->
                runCatching { a.generate(row.token, about) }
                    .onSuccess { Log.i(TAG, "urgent pass: $it (${about.joinToString().ifBlank { "the owner" }})") }
                    .onFailure { Log.i(TAG, "urgent pass not asked for: ${it.message}") }
            }
            // Only once the ship has taken them: a pass that failed
            // halfway must be free to say the same things again.
            if (record.isNotEmpty()) sent.putAll(record)
            forgets.forEach { sent.forget(s, it) }
            // One listing of the actions for the three things that
            // want them: what is open, the mirror, and the brief.
            val actions = runCatching { a.actions(row.token, status = "all") }
                .onFailure { Log.i(TAG, "actions skipped: ${it.message}") }.getOrNull()
            val settled = runCatching { runExecutor(a, row.token, actions) }
                .onFailure { Log.i(TAG, "messages skipped: ${it.message}") }.getOrDefault(emptyMap())
            // The listing as the mirror left it: what it finished is
            // neither shown as waiting nor told to the owner as waiting.
            val standing = actions?.map { act -> settled[act.id]?.let { act.copy(status = it) } ?: act }
            standing?.let { published(it.filter { act -> act.status in OPEN_STATUSES }) }
            // A reply the brief could not finish holds the mail cursor
            // where it is, so the next pass lists it again.
            val unfinished = runCatching { brief(a, row.token, s, url, nowMs, raw, freshMail, standing) }
                .onFailure { Log.w(TAG, "brief not sent: ${it.message}") }.getOrDefault(emptyList())
            val heldBack = unfinished.minOfOrNull { it.last }?.let { it - 1 } ?: Long.MAX_VALUE
            messagesCursor = minOf(messagesCursor, triaged.postFloor).coerceAtLeast(row.messagesCursor)
            db.orreryAccounts().upsert(
                row.copy(
                    messagesCursor = messagesCursor,
                    mailCursor = minOf(mailCursor, heldBack, triaged.mailFloor).coerceAtLeast(row.mailCursor),
                    calendarCursor = nowMs,
                ),
            )
            _lastPushMs.value = nowMs
            _error.value = if (refused == 0) null else "$refused refused: ${firstReason ?: "no reason given"}"
        } catch (e: OrreryError.Refused) {
            if (e.status == 403) {
                // The ship no longer takes this install's key: stop, and say so.
                turnOff()
                db.orreryAccounts().delete(s)
                _error.value = "The ship no longer accepts this install's key. Turn the pipe on again to mint a new one."
            } else {
                // A busy ship's 500 is a pass lost, not what it carried:
                // a claim confirmed in the tray has left the tray, and
                // this is the only copy of it.
                _error.value = e.message
                requeue()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Detached mid-pass: what was queued waits for the next one.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { requeue() }
            throw e
        } catch (e: Exception) {
            // Unreachable, or a shape the parsing did not expect: either
            // way the pass is lost and what it carried is not.
            _error.value = e.message ?: e::class.simpleName
            if (e !is OrreryError) Log.w(TAG, "pass failed", e)
            requeue()
        } finally {
            if (locked) passLock.unlock()
            _pushing.value = false
        }
    }

    /** The owner's zone as the last brief pass read it, for the loop's wake at seven. */
    private var briefZone: kotlinx.datetime.TimeZone? = null

    /** A phone gives a computer the first quarter hour to send the brief. */
    private val briefGrace: Long get() = if (io.nisfeb.talon.ui.isTouchPrimary) Brief.PHONE_GRACE_MS else 0L

    /** Measured once a run: a key that covered the schema a minute ago still does. */
    private var scopeChecked = false

    /**
     * A key lacking any kind, attribute or action kind the ship has is
     * replaced with one that has them all, and the old one revoked. Keys
     * minted before orrery had activities, or before the brief needed
     * sensitive: write, are what this catches. A key the ship will not
     * read the state for is left alone: that is the owner revoking it,
     * and a new key would overrule them.
     */
    private suspend fun ensureScope(s: String, row: OrreryAccountEntity) {
        if (scopeChecked) return
        val a = api ?: return
        // Twice a day is enough for a schema the owner changes by hand,
        // and a background pass builds a new repo every time: without
        // this it asked the ship for the schema and the state on every
        // wake, which is two seconds of the ship for an answer that
        // almost never differs.
        val checked = db.orrerySent().get(s, SCOPE_KEY)?.value?.toLongOrNull() ?: 0L
        if (now() - checked < SCOPE_GAP_MS) {
            scopeChecked = true
            return
        }
        val full = runCatching { a.schema() }.getOrNull() ?: return
        val mine = runCatching { a.stateJson(row.token)["schema"] as? JsonObject }.getOrNull() ?: return
        suspend fun measured() {
            scopeChecked = true
            db.orrerySent().put(io.nisfeb.talon.data.OrrerySentEntity(s, SCOPE_KEY, now().toString(), now()))
        }
        if (scopeCovers(mine, full)) {
            measured()
            return
        }
        val minted = runCatching { a.mint("Talon on $platform", by(), schemaKinds(full), schemaActions(full)) }
            .onFailure { Log.w(TAG, "could not mint a key with the whole scope: ${it.message}") }
            .getOrNull() ?: return
        db.orreryAccounts().upsert(row.copy(clientId = minted.id, token = minted.token))
        runCatching { a.revoke(row.clientId) }
        measured()
        Log.i(TAG, "replaced this install's key with one that has the whole scope")
    }

    /**
     * Which body a ship's person is, asked of the state view first, then
     * of resolve, and only then made up. A person the ship keeps under
     * another name is that person: writing to an id built from the @p
     * would be the twin all over again.
     */
    private class People(
        private val api: OrreryApi,
        private val token: String,
        private val sent: io.nisfeb.talon.data.OrrerySentDao,
        private val ourShip: String,
        bodies: List<KnownBody>,
    ) {
        private val byShip: Map<String, String> = bodies.mapNotNull { b -> b.ship?.let { it to b.id } }.toMap()
        private val names: List<Pair<String, String>> = bodies.filter { it.id.startsWith("person/") }.map { (it.name ?: "") to it.id }
        private val called: Map<String, Set<String>> =
            bodies.associate { b -> b.id to (b.aliases + listOfNotNull(b.name)).toSet() }
        private val decided = mutableMapOf<String, String>()

        /** What the ship already calls a body it has, or null when it has no such body. */
        fun goesBy(id: String): Set<String>? = called[id]

        /** Never suspends on the ship more than once per person per pass. */
        fun idFor(ship: String, name: String?): String {
            if (ship == ourShip) return "person/me"
            decided[ship]?.let { return it }
            val fromState = byShip[ship]
                ?: names.firstOrNull { (n, _) -> name != null && samePerson(n, name) }?.second
                ?: personId(ship)
            decided[ship] = fromState
            return fromState
        }
    }
    /** What one pass reads with: the ship's bodies, the model if any, and the gate. */
    private class Reading(
        val bodies: List<KnownBody>,
        val index: NameIndex,
        val attrs: Map<String, List<String>>,
        val notes: Map<String, Map<String, String>>,
        /** The action kinds the key may propose and their payload shapes. */
        val schema: JsonObject,
        val model: LocalModel?,
        val gate: PatternGate?,
        var modelRuns: Int = 0,
        /** The decision model, when it is on and there is a key for it. */
        val decider: Decider? = null,
        /** The gate's threshold when the gate is on, else null. */
        val threshold: Double? = null,
        /** The score a body needs for the reader to see it, when Jev chooses them, else null. */
        val keep: Double? = null,
        var day: DecideDay = DecideDay(),
        /** What the pass has done, written only once the ship has taken it. */
        var remember: (String, String) -> Unit = { _, _ -> },
        /** What a message has to score before the ship is asked for a pass now, or null. */
        val escalate: Double? = null,
        /**
         * The bodies an urgent message was about, rule 16. One request a
         * pass, however many messages say the same thing: the ship's
         * settle folds them anyway and the owner's daily cap is small.
         */
        var urgentAbout: List<String>? = null,
    ) {
        /** Every body id the pass can see: what a payload's refs are checked against. */
        val known: Set<String> by lazy { bodies.mapTo(HashSet()) { it.id } }

        /** What the ship calls [id], as the pass already read it. */
        fun nameOf(id: String): String? = bodies.firstOrNull { it.id == id }?.let { it.name ?: it.id }
    }

    /** The decision model, when the owner has turned it on and an OpenRouter key is set. */
    private fun decider(): Pair<Decider, DecideSettings>? {
        val d = decide?.settings?.value?.under(cloud?.config?.invoke())?.takeIf { it.on } ?: return null
        val key = cloud?.config?.invoke()?.let(::openRouterKey) ?: return null
        // The bare client: the ship's cookie has no business at OpenRouter.
        return OpenRouterDecider(bare, key, d) to d
    }

    /** Whether the decision model could run here: an OpenRouter key is set. */
    fun decideHasKey(): Boolean = cloud?.config?.invoke()?.let(::openRouterKey) != null

    private suspend fun reading(a: OrreryApi, row: OrreryAccountEntity, s: String, seen: StateView?): Reading? {
        // The pass reads the state once and hands it down: every read
        // costs the ship a second of its single thread.
        val view = seen ?: runCatching { a.state(row.token) }.getOrElse { Log.i(TAG, "state view skipped: ${it.message}"); return null }
        val model = cloudModelIfOn() ?: (if (isLocalTriageSupported) LocalModels.best()?.second else null)
        val emb = embedder
        val gate = if (model != null && emb != null) runCatching {
            val yes = db.orreryNoticed().snippets(s, "confirmed", GATE_EXAMPLES)
            val no = db.orreryNoticed().snippets(s, "discarded", GATE_EXAMPLES)
            // Built from up to a hundred embeddings, and the same
            // examples build the same gate, so it is built again only
            // when the tray has moved. A gate that could not be built
            // is tried again next pass.
            gateBuilt?.takeIf { it.first == yes to no }?.second
                ?: PatternGate.build(emb, yes, no)?.also { gateBuilt = (yes to no) to it }
        }.getOrNull() else null
        val dec = if (model != null) decider() else null
        return Reading(
            view.bodies, NameIndex(view.bodies), view.attrs, view.notes, view.schema, model, gate,
            decider = dec?.first, threshold = dec?.second?.takeIf { it.gate }?.threshold,
            escalate = dec?.second?.escalate,
            keep = dec?.second?.takeIf { it.relevance }?.keep,
        )
    }

    /**
     * The funnel over this pass's messages, the transcripts published
     * since the last, and the mail that arrived: the ones in scope go
     * through the rules and, where there is one, the model, against
     * the ship's own bodies. Each claim lands in the tray, or goes
     * straight up when the person has trusted that kind of claim. A
     * claim already noticed is the same row again.
     */
    private suspend fun triage(
        a: OrreryApi,
        row: OrreryAccountEntity,
        posts: List<io.nisfeb.talon.data.MessageEntity>,
        /** Calls transcribed since the last pass, taken off the queue by the pass. */
        spoken: List<Pair<String, List<Spoken>>>,
        s: String,
        nowMs: Long,
        url: String,
        freshMail: List<io.nisfeb.talon.mail.InboxEntry>,
        /** The people in the owner's book, as this pass already read them. */
        contacts: List<io.nisfeb.talon.data.ContactEntity>,
        /** The state as this pass read it, so the triage adds no read of its own. */
        view: StateView?,
        remember: (String, String) -> Unit,
    ): Triaged {
        // Status lines change when nothing is said, so they are counted
        // in before the pass decides it has nothing to do.
        val lines = contacts.mapNotNull { c -> contactStatus(c)?.let { (line, at) -> Triple(c.ship, line, at) } }
        val read = db.orrerySent().some(s, lines.map { "status:${it.first}" }).associate { it.key to it.value }
        val fresh = lines.filter { (ship, line, _) -> read["status:$ship"] != line.hashCode().toString(16) }
        if (posts.isEmpty() && spoken.isEmpty() && freshMail.isEmpty() && fresh.isEmpty()) return Triaged()
        // A phone with a computer on the job leaves the reading to it. The
        // phone's cursor still moves; the computer reads these from its
        // own, which did not. ponytail: a computer that never returns
        // leaves them unread; a second cursor would need a column, and
        // the table is already on testers' phones.
        if (io.nisfeb.talon.ui.isTouchPrimary && standDown?.on?.value == true) {
            val yielded = runCatching { computerActive(a.clients(), nowMs) }.getOrDefault(false)
            _yielding.value = yielded
            if (yielded) return Triaged()
        } else {
            _yielding.value = false
        }
        // No state, no reading, and nothing read: both cursors stay where
        // they were, so the next pass reads these. The defaults move them
        // past everything, which is right only when the pass did read.
        val r = reading(a, row, s, view) ?: return Triaged(postFloor = row.messagesCursor, mailFloor = row.mailCursor)
        r.remember = remember
        val allowed = db.orreryChannels().all().toSet()
        val ourNick = db.contacts().get(s)?.nickname
        var up = Facts()
        // A message is read once. The ship answers "existing" for an
        // observation it already holds, but the model costs a second
        // every time and its answer is not guaranteed to be the same.
        val handled = db.orrerySent().some(s, posts.map { "msg:${it.whom}/${it.id}" }).map { it.key }.toSet()
        // How far the cursors may move. A message the budget stopped
        // short of was marked read and the cursor went past it, so it
        // was never read by anything: the pass keeps the cursor behind
        // whatever it left, and the next one picks it up.
        var postFloor = Long.MAX_VALUE
        var mailFloor = Long.MAX_VALUE
        for (m in posts) {
            val key = "msg:${m.whom}/${m.id}"
            if (key in handled) continue
            if (r.modelRuns >= MODEL_PER_PASS) {
                postFloor = minOf(postFloor, m.sentMs - 1)
                break
            }
            val text = StoryCache.textFor(m.id, m.contentJson)
            remember(key, "")
            if (!inScope(m.whom, text, s, ourNick, allowed)) continue
            val kind = talonKind(m.whom)
            // A message is read with the ones before it: "yes, at 8"
            // says nothing alone. They are for reading only, and the
            // claims are held to the words of this one. Only a model
            // reads them, so with none there is nothing to fetch.
            val before = if (r.model == null) emptyList()
            else db.messages().before(m.whom, m.sentMs, ModelExtractor.CONTEXT_MESSAGES).reversed()
                .map { it.author to StoryCache.textFor(it.id, it.contentJson) }
                .filter { it.second.isNotBlank() }
            up += triageText(r, s, nowMs, text, m.author, m.sentMs, m.whom, m.id, kind, "talon://chat/${m.whom}?id=${m.id}", before)
        }
        // A call's words, by speaker: each run of one voice is one message.
        for ((address, lines) in spoken) {
            val said = mergeSpoken(lines)
            said.forEachIndexed { i, sp ->
                val before = said.subList(maxOf(0, i - ModelExtractor.CONTEXT_MESSAGES), i).map { it.ship to it.text }
                up += triageText(r, s, nowMs, sp.text, sp.ship, nowMs, address, "$i", "talon-call", "$address#$i", before)
            }
        }
        // A status line, read the way a message is read. Once per line:
        // the digest is of the words, so a line put back says nothing new.
        for ((ship, line, at) in fresh.take(STATUS_PER_PASS)) {
            remember("status:$ship", line.hashCode().toString(16))
            up += triageText(
                r, s, nowMs, line, ship, at.coerceAtMost(nowMs), "contact:$ship", ship,
                "contacts", "talon://profile/$ship",
            )
        }
        // Mail is addressed to us, so every message in a fresh thread is
        // in scope. A thread is read once at a given last message: the
        // cursor may be held behind a thread this pass left, and without
        // a record the ones beside it would be read again every pass.
        val mailApi = AuspexApi(http, url)
        val mailRead = db.orrerySent().some(s, freshMail.map { "mail:${it.id}" }).associate { it.key to it.value }
        var threads = 0
        for (e in freshMail) {
            val key = "mail:${e.id}"
            if (mailRead[key] == e.last.toString()) continue
            if (threads >= MAIL_THREADS_PER_PASS || r.modelRuns >= MODEL_PER_PASS) {
                mailFloor = minOf(mailFloor, e.last - 1)
                continue
            }
            val thread = runCatching { mailApi.thread(e.id) }.getOrNull()
            if (thread == null) {
                // Not read, so not past: the ship may answer next time.
                mailFloor = minOf(mailFloor, e.last - 1)
                continue
            }
            threads++
            remember(key, e.last.toString())
            for (msg in thread.messages) {
                if (msg.from == s || msg.body.isBlank()) continue
                up += triageText(r, s, nowMs, msg.body, msg.from, msg.sent.coerceAtMost(nowMs), "mail:${e.id}", msg.id, "mail", "talon://mail/${e.id}")
            }
        }
        tally(s, r.day, nowMs)
        return Triaged(up, postFloor, mailFloor, r.urgentAbout)
    }

    /**
     * What one triage read, and how far the cursors may go: a floor is
     * the last moment a cursor may take, so that whatever this pass did
     * not get to is still there for the next one.
     */
    private data class Triaged(
        val facts: Facts = Facts(),
        val postFloor: Long = Long.MAX_VALUE,
        val mailFloor: Long = Long.MAX_VALUE,
        /** The bodies to ask the ship to look at now, or null for the usual wait. */
        val urgentAbout: List<String>? = null,
    )

    /**
     * The day's count of what the decision model did, kept with the rest
     * of this install's orrery record and logged as two lines: what the
     * gate read and skipped against what both models cost, and what the
     * status check kept and dropped. Those lines are the case for both.
     */
    private suspend fun tally(s: String, add: DecideDay, nowMs: Long) {
        if (add == DecideDay()) return
        val day = localDay(nowMs)
        val now = keptDay(s, day) + add
        db.orrerySent().put(io.nisfeb.talon.data.OrrerySentEntity(s, "decide:$day", Json.encodeToString(DecideDay.serializer(), now), nowMs))
        io.nisfeb.talon.ai.AiSpend.add(io.nisfeb.talon.ai.AiFeature.OrreryTriage.name, add.analystUsd, nowMs)
        io.nisfeb.talon.ai.AiSpend.add(io.nisfeb.talon.ai.AiSpend.JEV, add.gateUsd + add.checkUsd + add.pickUsd, nowMs)
        now.lines(day).forEach { Log.i(TAG, it) }
        _decideToday.value = day to now
    }

    /** What the gate would have done over messages already read, for choosing its threshold. */
    data class GateCheck(
        val lines: List<String>,
        val threshold: Double,
        /** How many of the messages each threshold in the band would have let through. */
        val readAt: List<Pair<Double, Int>>,
        val costUsd: Double,
        val failed: Int,
        /** With body picks: for each cut-off, how many bodies the reader would see on average. */
        val keptAt: List<Pair<Double, Double>> = emptyList(),
    ) {
        val total: Int get() = lines.size
    }

    /**
     * The gate over the last [limit] messages Talon already holds that the
     * reader would read, not a new read of the chats: one line per
     * message with the probability and read or skip, and what each
     * threshold from 0.2 to 0.4 would have let through. Nothing is
     * written; only the decision model is asked.
     */
    /** A check under way, or done: how far it has got, and the result once there is one. */
    data class GateCheckRun(val done: Int, val total: Int, val result: Result<GateCheck>? = null)

    private val _gateCheck = MutableStateFlow<GateCheckRun?>(null)
    /** The gate check, run by the repo so that leaving Settings does not stop it. */
    val gateCheck: StateFlow<GateCheckRun?> = _gateCheck.asStateFlow()
    private var gateCheckJob: Job? = null

    fun startGateCheck(limit: Int = 300, picks: Boolean = false) {
        if (gateCheckJob?.isActive == true) return
        _gateCheck.value = GateCheckRun(0, 0)
        gateCheckJob = scope.launch {
            val r = checkGate(limit, picks) { done, total -> _gateCheck.value = GateCheckRun(done, total) }
            _gateCheck.value = GateCheckRun(_gateCheck.value?.total ?: 0, _gateCheck.value?.total ?: 0, r)
        }
    }

    fun stopGateCheck() {
        gateCheckJob?.cancel()
        gateCheckJob = null
        _gateCheck.value = null
    }

    suspend fun checkGate(limit: Int = 300, picks: Boolean = false, progress: (done: Int, total: Int) -> Unit = { _, _ -> }): Result<GateCheck> = runCatching {
        val a = api ?: error("Not attached to a ship.")
        val s = ship ?: error("Not attached to a ship.")
        val row = db.orreryAccounts().get(s) ?: error("Turn on Feed Orrery first.")
        val (dec, settings) = decider() ?: error(if (decideHasKey()) "Turn the decision model on first." else "Set OpenRouter as the AI provider, with its key, first.")
        val view = a.state(row.token)
        val index = NameIndex(view.bodies)
        val allowed = db.orreryChannels().all().toSet()
        val ourNick = db.contacts().get(s)?.nickname
        // What Talon already holds and the funnel would read: in scope,
        // free text, not a question. Not a new read of the chats.
        val walked = db.messages().postsBefore(now(), s, limit * 6)
        val picked = walked.asSequence()
            .map { it to StoryCache.textFor(it.id, it.contentJson) }
            .filter { (m, t) -> forTheGate(t) && inScope(m.whom, t, s, ourNick, allowed) }
            .take(limit).toList().reversed()
        progress(0, picked.size)
        // With picks, what Jev chose for each message the gate lets through.
        val chose = arrayOfNulls<Relevance.Picked>(picked.size)
        suspend fun ask(i: Int): Gate.Result {
            val (m, text) = picked[i]
            val earlier = db.messages().before(m.whom, m.sentMs, ModelExtractor.CONTEXT_MESSAGES).reversed()
                .map { StoryCache.textFor(it.id, it.contentJson) }.filter { it.isNotBlank() }
            val from = index.authorId(m.author, s)
            val ranked = rankBodies(view.bodies, index, text, earlier)
            val g = Gate.decide(dec, settings.threshold, text, from, earlier, ranked)
            if (picks && g.read && g.p != null) chose[i] = Relevance.pick(dec, text, from, earlier, ranked)
            return g
        }
        val results = Gate.askAll(picked.size, GATE_CHECK_AT_ONCE, ::ask, progress)
        val probs = mutableListOf<Double>()
        val lines = mutableListOf<String>()
        var cost = 0.0
        var failed = 0
        picked.forEachIndexed { i, (m, text) ->
            val g = results[i]
            cost += g.costUsd
            val p = g.p
            if (p == null) failed++ else probs += p
            val shown = p?.let { (kotlin.math.round(it * 100) / 100).toString().padEnd(4, '0') } ?: " -- "
            lines += "$shown ${if (g.read) "read" else "skip"} | ${m.author}: ${text.take(90).replace('\n', ' ')}"
            chose[i]?.let { c ->
                cost += c.costUsd
                lines += if (c.failed) "      bodies: ${c.note}"
                else "      bodies: " + c.above(0.1).take(8).joinToString(", ") { (id, sc) -> "$id ${(kotlin.math.round(sc * 100) / 100)}" }.ifBlank { "none above 0.1" }
            }
        }
        val band = listOf(0.2, 0.25, 0.3, 0.35, 0.4).map { t -> t to (probs.count { it >= t } + failed) }
        val answered = chose.filterNotNull().filter { !it.failed }
        val keptAt = if (answered.isEmpty()) emptyList()
        else listOf(0.3, 0.5, 0.7).map { t -> t to answered.sumOf { it.above(t).size }.toDouble() / answered.size }
        GateCheck(lines, settings.threshold, band, cost, failed, keptAt).also {
            Log.i(TAG, "gate check: ${it.total} messages, ${band.joinToString { (t, n) -> "$n read at $t" }}, cost ${dollars(cost)}")
        }
    }

    /** One text through the rules and the model; what it claims goes to the tray or up. */
    private suspend fun triageText(
        r: Reading,
        s: String,
        nowMs: Long,
        text: String,
        author: String,
        atMs: Long,
        whom: String,
        postId: String,
        kind: String,
        sourceId: String,
        /** What was said before this, in the same conversation, for reading only. */
        context: List<Pair<String, String>> = emptyList(),
    ): Facts {
        var up = Facts()
        // The rules first, then the model where there is one: the same
        // claim from both is one row, and the rules got there.
        val byRules = ruleFacts(text, author, atMs, s, r.index)
        val emb = embedder
        // The gate, once the person has taught it: a message that reads
        // like what they discard does not spend a model run.
        val worth = r.gate == null || emb == null ||
            (runCatching { emb.embed(text) }.getOrNull()?.let { r.gate.worthAModel(it) } ?: true)
        var byModel: List<Noticed> = emptyList()
        var plan: ModelExtractor.Plan? = null
        // A question states nothing, and the analyst never reads one, so
        // neither does the gate.
        val earlier = context.map { it.second }
        // What the message names first, then people: wherever a list is
        // cut, this decides what is cut. Worked out once, and only if
        // the reader or the escalate question asks for it.
        val ranked by lazy { rankBodies(r.bodies, r.index, text, earlier) }
        if (r.model != null && worth && r.modelRuns < MODEL_PER_PASS && forTheReader(text)) {
            val model = r.model
            val dec = r.decider
            val from = r.index.authorId(author, s)
            val say: (String) -> Unit = { Log.i(TAG, "$sourceId $it") }
            val analyst: suspend () -> List<Noticed> = {
                r.modelRuns++
                // Jev chooses what the reader sees, when asked to: the few
                // bodies the message is about rather than the first sixty.
                val keep = r.keep
                val seen = if (dec != null && keep != null) {
                    val p = Relevance.pick(dec, text, from, earlier, ranked)
                    say(p.note)
                    val chosen = Relevance.chosen(ranked, p, keep, from)
                    r.day = r.day.copy(picked = r.day.picked + 1, pickedBodies = r.day.pickedBodies + chosen.size, pickUsd = r.day.pickUsd + p.costUsd)
                    chosen
                } else ranked
                ModelExtractor.extract(model, r.index, seen, text, author, atMs, s, r.attrs, r.notes, context, onPlan = { plan = it })
                    .also { r.day = r.day.copy(analystUsd = r.day.analystUsd + (model.lastCostUsd ?: 0.0)) }
            }
            byModel = if (dec != null && r.threshold != null && forTheGate(text)) {
                val (g, rows) = Gate.around(dec, r.threshold, text, from, earlier, ranked, say, analyst)
                r.day = r.day.copy(
                    read = r.day.read + (if (g.read) 1 else 0),
                    skipped = r.day.skipped + (if (g.read) 0 else 1),
                    gateUsd = r.day.gateUsd + g.costUsd,
                )
                rows
            } else analyst()
            // Rule 8, held by a model that cannot answer outside the set:
            // a status that is a feeling never reaches the tray or the ship.
            if (dec != null && byModel.isNotEmpty()) {
                val (kept, t) = StatusCheck.filter(dec, text, from, byModel, say)
                byModel = kept
                r.day += t
            }
        }
        // A plan fixed in time is the owner's to put on the calendar: a
        // proposal on the ship, about the author and whom it names.
        plan?.let { p ->
            val about = (listOf(r.index.authorId(author, s)) + r.index.find(text).map { it.first.id }).filter { r.index.has(it) }.distinct().take(5)
            proposePlan(s, sourceId, p, about, r)
        }
        // A cancelled occurrence is written as a fact whatever else
        // happens; the calendar still holding it is the owner's to
        // decide, so it is offered rather than done.
        for (n in (byRules + byModel)) {
            if (n.attr != "skipped" || !n.subject.startsWith("activity/")) continue
            val iso = n.value.asText() ?: continue
            proposeCancel(s, sourceId, n.subject, iso, r)
        }
        // Rule 16: the reader is the only thing with the words in front
        // of it, so the reader decides whether this is a thing somebody
        // needs help with inside the hour. Asked once the claims have
        // survived validation, and at most once a pass.
        val kept = byRules + byModel
        val esc = r.escalate
        if (r.decider != null && esc != null && kept.isNotEmpty() && r.urgentAbout == null) {
            val p = Escalate.sure(r.decider, text, r.index.authorId(author, s), earlier, ranked, kept)
            if (p >= esc) {
                r.urgentAbout = Escalate.about(kept)
                Log.i(TAG, "$sourceId reads as help needed within the hour ($p): asking the ship for a pass")
            }
        }
        for (n in kept) {
            val trusted = trusted(s, n.attr)
            val entity = OrreryNoticedEntity(
                id = noticedId(sourceId, n.subject, n.attr, n.value), ship = s, subject = n.subject, attr = n.attr,
                valueJson = n.value.toString(), atMs = n.atMs, untilMs = n.untilMs, conf = n.conf,
                sourceKind = kind, sourceId = sourceId, bodyJson = n.body?.toJson()?.toString(),
                whom = whom, postId = postId, snippet = text.take(200),
                state = if (trusted) "confirmed" else "pending", createdMs = nowMs,
            )
            db.orreryNoticed().insertIfNew(entity)
            // The claim goes to the ship until the ship has taken it. It
            // used to go only on the pass that first noticed it, so a
            // pass that failed after the row was written left a claim
            // confirmed here that the ship was never told about.
            val told = "fact:${entity.id}"
            if (trusted && db.orrerySent().get(s, told) == null) {
                up += factsOf(entity)
                r.remember(told, "")
            }
        }
        return up
    }

    /**
     * A plan a message fixed in time, filed once per message as a
     * calendar action for the owner to approve. The ship answers a twin
     * of an open one with that one, and this install remembers the
     * message, so a replayed pass files nothing new.
     */
    private suspend fun proposePlan(s: String, sourceId: String, p: ModelExtractor.Plan, about: List<String>, r: Reading) {
        val key = "plan:$sourceId"
        if (db.orrerySent().get(s, key) != null) return
        proposeCalendar(s, sourceId, key, ModelExtractor.planAction(p, about), r, "${p.title} at ${isoUtc(p.startMs)}")
    }

    /**
     * One calendar action, proposed once under [key]. The ship's shapes,
     * not ours, rule 14: a kind it does not list, or a payload its shape
     * refuses, is a line in the log and never a request.
     */
    private suspend fun proposeCalendar(s: String, sourceId: String, key: String, body: JsonObject, r: Reading, what: String) {
        val a = api ?: return
        val token = keyToken() ?: return
        if ("calendar" !in schemaActions(r.schema)) return Log.i(TAG, "$sourceId $what dropped: the schema lists no calendar action")
        val shape = (r.schema["payloads"] as? JsonObject)?.get("calendar") as? JsonObject ?: JsonObject(emptyMap())
        val (payload, why) = checkPayload(body["payload"] as JsonObject, shape, r.known)
        if (payload == null) return Log.i(TAG, "$sourceId $what dropped: $why")
        runCatching { a.act(JsonObject(body + ("payload" to payload)), token) }
            .onSuccess { (id, _) ->
                db.orrerySent().put(io.nisfeb.talon.data.OrrerySentEntity(s, key, id, now()))
                Log.i(TAG, "$sourceId proposed $what")
            }
            .onFailure { Log.i(TAG, "$sourceId $what not proposed: ${it.message}") }
    }

    /**
     * The other half of a cancelled occurrence: the calendar still has
     * it.
     *
     * A `skipped` row says the evening is off, and says nothing to the
     * calendar, which goes on showing it and reminding about it. Rule
     * 14: where the client knows the event the occurrence came from,
     * it may propose taking it off, and this client does know, because
     * its own pipe wrote the body from that event and kept the tie.
     *
     * A proposal, never a write. Taking something off a calendar is a
     * tap, and `calendar` is not a kind the ship does unasked.
     */
    private suspend fun proposeCancel(s: String, sourceId: String, subject: String, skippedIso: String, r: Reading) {
        // One proposal per occurrence, not per message: two people
        // saying practice is off should not ask the owner twice.
        val key = "uncal:$subject/$skippedIso"
        if (db.orrerySent().get(s, key) != null) return
        val at = parseIsoUtc(skippedIso) ?: return
        val event = calendarEventFor(s, subject) ?: return Log.i(TAG, "$sourceId cancel not proposed: no calendar event for $subject")
        // The ship matches the occurrence by the moment it really
        // starts, so the calendar's own instant for that day beats the
        // model's reading of "tonight" wherever the pipe has one.
        val starts = occurrenceOn(s, event, at) ?: at
        val name = r.nameOf(subject) ?: subject.substringAfter('/')
        proposeCalendar(s, sourceId, key, cancelAction(subject, name, event.second, starts), r, "taking $name off the calendar at ${isoUtc(starts)}")
    }

    /**
     * The calendar and event this body was written from, as the pipe
     * remembered it: `cal:<calendar>/<uid>` holds the body id, so the
     * way back is a scan of what this install has written.
     */
    private suspend fun calendarEventFor(s: String, subject: String): Pair<String, String>? =
        db.orrerySent().under(s, "cal:")
            .firstOrNull { it.value.substringBefore('|') == subject }
            ?.key?.removePrefix("cal:")
            ?.let { ref ->
                val cal = ref.substringBefore('/')
                val uid = ref.substringAfter('/')
                if (cal.isBlank() || uid.isBlank()) null else cal to uid
            }

    /**
     * The realized start of the occurrence on the same day as [nearMs],
     * off the occurrence records the pipe keeps, or null where it kept
     * none. The calendar skips by the exact moment, so a reading of
     * "tonight" that landed on the wrong hour would skip nothing.
     */
    private suspend fun occurrenceOn(s: String, event: Pair<String, String>, nearMs: Long): Long? {
        val prefix = "occ:${event.first}/${event.second}/"
        return occurrenceNear(
            db.orrerySent().under(s, prefix).mapNotNull { it.key.removePrefix(prefix).toLongOrNull() },
            nearMs,
        )
    }

    /** The person's word on an action: done, dismissed, or failed with why. */
    suspend fun setAction(id: String, status: String, note: String = ""): Result<Unit> = runCatching {
        val a = api ?: error("Not attached to a ship.")
        val s = ship ?: error("Not attached to a ship.")
        // This install's key where it has one, else the owner's own say.
        a.transition(db.orreryAccounts().get(s)?.token, id, status, note)
        _actions.value = settledActions(_actions.value, id, status)
        // The mirror reads every action and the whole calendar before it
        // makes the todo: seconds on a busy ship, so it runs behind the
        // answer, never in its way.
        scope.launch { refreshActions() }
    }

    /**
     * Send what the owner typed under a proposal, rule 17. The ship
     * revises the action in place and may propose more beside it; the
     * answer is what the screen redraws from, never what was sent,
     * since the ship may have resolved a name loosely spelled, kept a
     * time it could not move, or refused outright.
     *
     * It stays proposed either way: approving is the same tap it was.
     */
    suspend fun refine(id: String, text: String): Result<io.nisfeb.talon.orrery.Refined> = runCatching {
        val a = api ?: error("Not attached to a ship.")
        val s = ship ?: error("Not attached to a ship.")
        val answer = a.refine(db.orreryAccounts().get(s)?.token, id, text)
        answer.action?.let { revised ->
            _actions.value = (_actions.value.map { if (it.id == revised.id) revised else it } + answer.extras)
                .distinctBy { it.id }
        }
        answer
    }

    /**
     * The same, taken at once: the lists change now and the ship is told
     * behind them, so nothing waits on it. A refusal puts the action back
     * as it was and says why under Orrery in Settings.
     */
    fun answer(id: String, status: String, note: String = "") {
        val was = _actions.value.firstOrNull { it.id == id }
        _actions.value = settledActions(_actions.value, id, status)
        // Answered here: its notification goes now, not on the next read.
        onActions?.invoke(emptyList(), setOf(id))
        scope.launch {
            setAction(id, status, note).onFailure { e ->
                if (was != null) _actions.value = listOf(was) + _actions.value.filterNot { it.id == id }
                _error.value = "Orrery did not take that answer: ${e.message ?: "no reason given"}"
                Log.w(TAG, "answer $status on $id refused: ${e.message}")
            }
        }
    }


    /**
     * What is waiting for an answer, read now. Needs nothing but orrery
     * on the ship: the pipe feeds orrery, and answering it is another
     * matter.
     */
    suspend fun refreshActions() {
        val a = api ?: return
        if (ship == null) return
        val token = keyToken()
        // One read of every action serves both: the open ones are what
        // the screen shows, which is what `?status=open` would answer,
        // and the executor settles its own from the rest.
        val all = token?.let { runCatching { a.actions(it, status = "all") }.getOrNull() }
        runCatching { all?.filter { it.status in OPEN_STATUSES } ?: a.actions(token) }
            .onSuccess { published(it) }
            .onFailure { Log.i(TAG, "actions skipped: ${it.message}") }
        a.generatorLast()?.let { _generator.value = it }
        // An approved task becomes a todo wherever it can be approved,
        // not only on the install that runs the pipe.
        runCatching { runExecutor(a, token, all) }.onFailure { Log.i(TAG, "messages skipped: ${it.message}") }
    }

    /** The cloud rung, opened once, only while the person has it on and a key is set. */
    private suspend fun cloudModelIfOn(): LocalModel? {
        val c = cloud ?: return null
        if (!c.on.value) return null
        if (c.rung.status() != RungStatus.Ready) return null
        return cloudModel ?: runCatching { c.rung.open() }.getOrNull()?.also { cloudModel = it }
    }

    /** Where the ladder stands on this device, for Settings. */
    suspend fun refreshModel() {
        val c = cloud
        if (c != null && c.on.value) { _model.value = c.rung.name to c.rung.status(); return }
        if (!isLocalTriageSupported) { _model.value = null; return }
        val all = LocalModels.statuses()
        val pick = all.firstOrNull { it.second == RungStatus.Ready }
            ?: all.firstOrNull { it.second is RungStatus.NeedsDownload }
            ?: all.firstOrNull()
        _model.value = pick?.let { it.first.name to it.second }
    }

    /** Fetch what the best downloadable rung needs, then re-walk the ladder. */
    suspend fun prepareModel(): Result<Unit> = runCatching {
        val rung = LocalModels.statuses().firstOrNull { it.second is RungStatus.NeedsDownload }?.first ?: return@runCatching
        _download.value = 0f
        try {
            rung.prepare { _download.value = it }
            LocalModels.reset()
        } finally {
            _download.value = null
        }
        refreshModel()
    }

    /** A kind of claim the person has confirmed three times and never discarded goes up on its own. */
    private suspend fun trusted(s: String, attr: String): Boolean =
        db.orreryNoticed().countByState(s, attr, "discarded") == 0 && db.orreryNoticed().countByState(s, attr, "confirmed") >= TRUST_AFTER

    private fun by(): String =
        "talon/" + platform.lowercase().replace(Regex("[^a-z0-9.]+"), "-").trim('-').take(50)

    private fun now(): Long = nowMs()

    companion object {
        private const val TAG = "OrreryRepo"

        /** The attached pipe, for the places that make a fact and hold no repo. */
        @kotlin.concurrent.Volatile
        private var current: OrreryRepo? = null

        /**
         * Facts made somewhere with no repo in hand, such as a transcript
         * just published. Pushed on the next pass, which is asked for at
         * once. Dropped when the pipe is off, since nothing would carry
         * them; a pass that fails keeps them for the next one.
         */
        fun note(facts: Facts) = enqueue { pending += facts }

        /**
         * A call, whose facts are made inside the next pass: that is where
         * the ship is asked who each speaker is, so a person it keeps under
         * another id is that person and not a twin named from the @p.
         */
        fun noteCall(make: (idFor: (ship: String, name: String?) -> String) -> Facts) = enqueue { calls += make }
        // ponytail: fixed window; a setting when somebody asks for one.
        const val BACKFILL_MS = 30L * 24 * 60 * 60 * 1000
        const val AHEAD_MS = 90L * 24 * 60 * 60 * 1000
        const val PUSH_EVERY_MS = 10L * 60 * 1000
        /** How often what is waiting is read again while attached, as a net under the beacon: one small request. */
        const val ACTIONS_EVERY_MS = 15L * 60 * 1000
        private const val BEACON_PATH = "/grubbery/api/keep/apps/shell.shell/desks/orrery.desk/desk/data/orrery.orrery_app/beacon/rev"
        /** Decision calls the gate check has in flight at once. */
        const val GATE_CHECK_AT_ONCE = 6
        private const val BRIEF_LEASE = "orrery-brief"
        const val MESSAGES_PER_PASS = 2000
        /** Posts under the cursor walked again each pass for ones that synced late. */
        const val LATE_POSTS = 500
        /** What the ship calls open: the three statuses `?status=open` answers with. */
        val OPEN_STATUSES = setOf("proposed", "approved", "claimed")

        /** When the key's scope was last measured against the ship's schema. */
        private const val SCOPE_KEY = "scope:checked"
        private const val SCOPE_GAP_MS = 12L * 60 * 60 * 1000

        const val MAIL_PER_PASS = 200

        /** How many threads a page of the cursor's listing asks for. */
        /** What the last brief suggested, by its day, in the table the reply cursor uses. */
        private const val SAID = "brief-said:"

        /** One orrery pass at a time in this process, whoever asked for it. */
        private val passLock = kotlinx.coroutines.sync.Mutex()

        /** A send that went out on a pass that ended before it could say so. */
        internal const val UNCONFIRMED = "sent, though the pass ended before it could say so"
        const val MAIL_PAGE = 20
        const val TRUST_AFTER = 3
        // ponytail: a per-pass cap; a per-day budget when a phone needs one.
        const val MODEL_PER_PASS = 20

        /** Status lines read in one pass, so a first run does not spend every model run on them. */
        const val STATUS_PER_PASS = 5
        const val GATE_EXAMPLES = 50
        const val MAIL_THREADS_PER_PASS = 10

        /** A noticed row as the facts it stands for. */
        fun factsOf(n: OrreryNoticedEntity): Facts = Facts(
            bodies = listOfNotNull(n.bodyJson?.let { bodyOf(Json.parseToJsonElement(it).jsonObject) }),
            observations = listOf(Obs(n.subject, n.attr, Json.parseToJsonElement(n.valueJson), n.atMs, n.untilMs, n.conf, n.sourceKind, n.sourceId)),
        )

        private fun bodyOf(o: JsonObject) = OBody(
            id = o["id"]!!.jsonPrimitive.content,
            name = o["name"]?.jsonPrimitive?.content,
            aliases = o["aliases"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content },
        )

        /**
         * The person's word on a noticed claim. Confirming sends it up
         * through the attached pipe; false means there is none to send
         * it through, and the row stays pending for when there is.
         */
        suspend fun confirm(db: AppDatabase, id: String): Boolean {
            val n = db.orreryNoticed().get(id) ?: return false
            // The word is taken whether or not a pipe is attached: the
            // row leaves the tray and teaches the gate either way. With
            // no pipe the button did nothing at all, and a tray left
            // over from a pipe since turned off could not be cleared.
            db.orreryNoticed().setState(id, "confirmed")
            val repo = current ?: return false
            if (!repo._enabled.value) return false
            note(factsOf(n))
            return true
        }

        suspend fun discard(db: AppDatabase, id: String) = db.orreryNoticed().setState(id, "discarded")

        /**
         * The words of a call just transcribed, read on the next pass by
         * speaker. Dropped when the pipe is off, like [note].
         */
        fun noteTranscript(address: String, lines: List<Spoken>) {
            if (lines.isNotEmpty()) enqueue { transcripts += address to lines }
        }

        /**
         * Hand something to the live repo for its next pass, asked for
         * at once. Nothing is queued while the pipe is off, since no pass
         * would carry it; a pass that fails keeps it for the next one.
         */
        private fun enqueue(add: OrreryRepo.() -> Unit) {
            val repo = current?.takeIf { it._enabled.value } ?: return
            repo.scope.launch {
                repo.pendingLock.withLock { repo.add() }
                repo.push()
            }
        }
    }
}

/** The key's id is the part of the token before the dot; the ship answers it separately too. */

/** The open list after an answer: approved stays, to be done; anything else has left it. */
internal fun settledActions(list: List<OrreryAction>, id: String, status: String): List<OrreryAction> =
    if (status == "approved" || status == "claimed") list.map { if (it.id == id) it.copy(status = status) else it }
    else list.filterNot { it.id == id }
