package io.nisfeb.talon.orrery

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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.datetime.Instant
import io.nisfeb.talon.util.nowMs
import kotlinx.datetime.toLocalDateTime
import io.nisfeb.talon.ui.parseIsoUtc
import io.nisfeb.talon.urbit.asText

/**
 * The structural pipe into orrery: what Talon knows for certain about
 * contacts and calls, pushed to the ship as observations under a key
 * this install minted for itself. The owner's chats, calendar and mail
 * the ship reads itself, and it writes the daily brief and sends what
 * the owner approved.
 *
 * Off until the user turns it on. Turning it on mints the key; turning
 * it off revokes the key on the ship and forgets it here. While on, a
 * pass runs every ten minutes. Observation ids are content hashes, so
 * a pass said again costs a resend the ship answers `existing`, never
 * a duplicate.
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
    /** The decision model's switches: the gate before the reader and the status check after it. */
    val decide: DecideControl? = null,
    /** The key's client, which carries no cookie. A test hands in its own ship. */
    bareClient: HttpClient? = null,
    /** Location sharing, where the platform has it, which the pipe stops and holds. */
    private val location: io.nisfeb.talon.ui.LocationControl = io.nisfeb.talon.ui.NoopLocationControl,
    /** The model a test reads with, in place of the ladder, as [bareClient] is its ship. */
    private val readWith: LocalModel? = null,
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
    /** What the last failed probe said, which the next good one may clear. */
    private var probeSaid: String? = null
    private val _answerProblem = MutableStateFlow<String?>(null)

    /**
     * An answer the ship refused, until the next answer. In [error] it
     * went with the next pass, often before the person who tapped
     * Approve had looked up.
     */
    val answerProblem: StateFlow<String?> = _answerProblem.asStateFlow()
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
    /**
     * The ship's own reader of the owner's Tlon chats, as the ship last
     * answered: null until asked, or where it cannot say. Talon reads no
     * chats itself, so this is the only reader of them.
     */
    private val _chatReader = MutableStateFlow<ChatReader?>(null)
    val chatReader: StateFlow<ChatReader?> = _chatReader.asStateFlow()
    private val _chatReaderRun = MutableStateFlow<ChatReaderRun?>(null)
    /** The chat reader's last pass. */
    val chatReaderRun: StateFlow<ChatReaderRun?> = _chatReaderRun.asStateFlow()

    /** Ask the ship for its chat reader's settings and last pass; false where it did not say. */
    suspend fun loadChatReader(): Boolean {
        val a = api ?: return false
        runCatching { lastChatRun(a) }.onSuccess { _chatReaderRun.value = it }
        return runCatching { chatReaderOf(Json.parseToJsonElement(a.settingsDoc("chat")).jsonObject) }
            .onSuccess { _chatReader.value = it }.isSuccess
    }

    private suspend fun lastChatRun(a: OrreryApi) = chatReaderRunOf(Json.parseToJsonElement(a.settingsDoc("chat/last")).jsonObject)

    /** Change the chat reader's settings; the ship's answer is the new state. */
    suspend fun setChatReader(body: JsonObject): Result<Unit> = writeSettings("chat", body).map { }

    private val _mailReader = MutableStateFlow<Boolean?>(null)
    /** Whether the ship reads the owner's mail (orrery 52); null until it says. */
    val mailReader: StateFlow<Boolean?> = _mailReader.asStateFlow()
    private val _mailReaderRun = MutableStateFlow<ChatReaderRun?>(null)
    val mailReaderRun: StateFlow<ChatReaderRun?> = _mailReaderRun.asStateFlow()

    /** Ask the ship whether its mail reader is on, and for its last pass; false where it did not say. */
    suspend fun loadMailReader(): Boolean {
        val a = api ?: return false
        runCatching { chatReaderRunOf(Json.parseToJsonElement(a.settingsDoc("mail/last")).jsonObject) }.onSuccess { _mailReaderRun.value = it }
        return runCatching { mailReaderOn(Json.parseToJsonElement(a.settingsDoc("mail")).jsonObject) }
            .onSuccess { _mailReader.value = it }.isSuccess
    }

    private fun mailReaderOn(o: JsonObject) = o["enabled"]?.jsonPrimitive?.booleanOrNull == true

    /** Turn the ship's mail reader on or off; the ship's answer is the new state. */
    suspend fun setMailReader(on: Boolean): Result<Unit> = writeSettings("mail", buildJsonObject { put("enabled", on) }).map { }

    /** What the chat reader may pick from: the ship's DMs, then its channels, each with a name. */
    suspend fun chatOptions(): Result<Pair<List<ChatOption>, List<ChatOption>>> = runCatching {
        val a = attached()
        // An empty list comes with the ship's reason (no groups desk, a
        // road refused), which is what the picker then says.
        suspend fun list(name: String): List<ChatOption> {
            val o = Json.parseToJsonElement(a.settingsDoc(name)).jsonObject
            val items = chatOptionsOf(o)
            val note = o["note"]?.jsonPrimitive?.contentOrNull
            if (items.isEmpty() && !note.isNullOrBlank()) error(note)
            return items
        }
        list("chat/dms") to list("chat/channels")
    }

    /**
     * Run the chat reader now, and wait a few seconds for its record to
     * say it is done: true when it did. A pass the model takes longer
     * over shows on the next [loadChatReader].
     */
    suspend fun wakeChatReader(): Result<Boolean> = runCatching {
        val a = attached()
        val before = _chatReaderRun.value?.atMs
        a.wakeChat()
        a.settle { lastChatRun(a).also { _chatReaderRun.value = it }.atMs != before }
    }

    /** True while this phone is leaving the reading to a computer. */
    private val _yielding = MutableStateFlow(false)
    val yielding: StateFlow<Boolean> = _yielding.asStateFlow()

    private var api: OrreryApi? = null
    // Coroutines only touch this, so a mutex is the whole of the guard
    // (commonMain has no synchronized: iOS is native).
    private val calls = mutableListOf<((String, String?) -> String) -> Facts>()
    private val transcripts = mutableListOf<Heard>()
    private val pendingLock = Mutex()
    /** The ship the queues hold words for. Under [pendingLock]. */
    private var queuedFor: String? = null
    /** The ship this repo is attached to, as this install reaches it. */
    var shipUrl: String? = null
        private set
    private var ship: String? = null
    private var loop: Job? = null
    private var attaching: Job? = null
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
    suspend fun setGenerator(enabled: Boolean, url: String? = null, model: String? = null, key: String? = null): Result<Unit> =
        writeSettings(
            "generator",
            buildJsonObject {
                put("enabled", enabled)
                url?.let { put("url", it) }
                model?.let { put("model", it) }
                key?.takeIf { it.isNotBlank() }?.let { put("api_key", it) }
            },
        ).map { }

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
        // Kept, so a detach cancels it: a probe that answered after a
        // ship switch started a loop and a beacon for the ship just left.
        attaching = scope.launch {
            probe()
            _enabled.value = db.orreryAccounts().get(ship) != null
            if (_enabled.value) startLoop()
            // A ship with no pipe has nowhere to send a move, and its
            // screen has no switch to stop them: the phone kept waking
            // for every one after a switch from a ship that had a pipe.
            // Held, not turned off: there is one switch, and turning it
            // off here turned it off for the ship that has a pipe.
            location.pause(!_enabled.value)
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

    /**
     * Merge into a settings document: a field left out is kept, one sent
     * as null is cleared, and a blank credential keeps the stored one.
     * The ship answers with the document as stored once the write has
     * landed, and that answer is what the screens then show: a write
     * from anywhere, the assistant included, reaches them.
     */
    suspend fun writeSettings(name: String, body: JsonObject): Result<String> = runCatching {
        val said = attached().setSettingsDoc(name, body)
        // Only an answer that is the document: anything else is left for
        // the next read rather than shown as everything turned off.
        (runCatching { Json.parseToJsonElement(said) }.getOrNull() as? JsonObject)?.takeIf { "enabled" in it }?.let { doc ->
            if (name == "generator") _generatorSettings.value = generatorSettingsOf(doc)
            if (name == "chat") _chatReader.value = chatReaderOf(doc)
            if (name == "mail") _mailReader.value = mailReaderOn(doc)
        }
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
        attaching?.cancel()
        attaching = null
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
        _chatReader.value = null
        _chatReaderRun.value = null
        scopeChecked = false
    }

    /**
     * Whether the queues may give or take words for [s], under
     * [pendingLock]. They hold one ship's at a time: the repo outlives a
     * ship switch on Android, and a call on one ship once rode the next
     * pass to the other. Another ship's leftovers are dropped here.
     */
    private fun holding(s: String): Boolean {
        if (ship != s) return false
        if (queuedFor != s) {
            calls.clear()
            transcripts.clear()
            queuedFor = s
        }
        return true
    }

    /** The pipe's loop stops. */
    private fun stopPipe() {
        // Off before the cancel: a pass cancelled here puts back what it
        // took only while the pipe is on, and read the other way round it
        // could still see it on.
        _enabled.value = false
        loop?.cancel()
        loop = null
    }

    /**
     * The pipe is off for good: turned off, or its key refused. The
     * location switch lives under the pipe, so it goes off the screen
     * with it, and is turned off with it: left on, the phone kept
     * waking for moves with nowhere to send them and no way to say
     * stop.
     *
     * Only here, not on detach; a ship with no pipe holds it instead
     * (see [attach]). Detaching is a restart, a ship switch, an Activity going away:
     * turning the saved switch off there put it off on every cold start
     * and every time the app was swiped away, which is exactly when
     * hearing moves with the app closed matters.
     */
    private fun turnOff() {
        stopPipe()
        location.stop()
    }

    suspend fun probe() {
        val a = api ?: return
        // A probe cancelled (its screen was left) is not an absent orrery:
        // caught as one, the Actions rail went and AI settings said
        // "not asked yet" until something probed again.
        io.nisfeb.talon.util.runSuspendCatching { a.probe() }
            .onSuccess {
                _availability.value = it
                // Only its own failure is its to clear: the reason the
                // pipe turned itself off went with any probe that worked.
                if (_error.value != null && _error.value == probeSaid) _error.value = null
                probeSaid = null
            }
            .onFailure { _availability.value = OrreryAvailability.UNKNOWN; probeSaid = it.message; _error.value = it.message }
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
        // Best effort: a writer slower than this is covered by the grace
        // a new key gets in the pass's 403 (see [minted]).
        a.keyLanded(key.token)
        rowLock.withLock {
            db.orreryAccounts().upsert(OrreryAccountEntity(s, key.id, key.token))
            minted(s, key.id)
        }
        _enabled.value = true
        location.pause(false)
        _error.value = null
        startLoop()
    }

    /** Revoke the key on the ship and forget it here. */
    suspend fun disable(): Result<Unit> = runCatching {
        val s = ship ?: return@runCatching
        // All under the lock a pass writes back under and replaces the
        // key under: one still running cannot put back the row this
        // deletes, and a key replaced between the revoke and the delete
        // stayed good on the ship with nobody holding it.
        rowLock.withLock {
            // The one step that can fail goes first. It used to go after
            // the loop was stopped and the records wiped, so an unreachable
            // ship left the switch on, the pipe dead and every record gone,
            // and the next pass would have made twins of what it had merged.
            db.orreryAccounts().get(s)?.let { row ->
                // A key the ship has already dropped answers 404; that is
                // the state we want, not a failure to report.
                runCatching { api?.revoke(row.clientId) }
                    .onFailure { if (it !is OrreryError.Refused || it.status != 404) throw it }
            }
            // Off before the row goes, so nothing between the two sees the
            // pipe on and keeps words for it.
            turnOff()
            db.orrerySent().clear(s)
            db.orreryAccounts().delete(s)
        }
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
                } catch (e: Throwable) {
                    // Throwable: an Error from a local model's runtime
                    // ended the loop here, and nothing said so.
                    Log.w(TAG, "pass failed", e)
                    _error.value = e.message ?: e::class.simpleName
                }
                // A failed pass waits longer each time (see the failure rule
                // in [push]), in steps of ten minutes, so a pass that
                // succeeds from elsewhere, a push from the screen or a
                // confirm, ends a long wait at the next step.
                var left = if (failuresInARow > 0) backoff(failuresInARow) + kotlin.random.Random.nextLong(0, 60_000) else PUSH_EVERY_MS
                while (left > 0) {
                    val step = minOf(left, PUSH_EVERY_MS)
                    delay(step)
                    left = if (failuresInARow == 0) 0 else left - step
                }
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
     * One pass over every source. Safe to call any time, and safe to
     * run again from nothing: the ship is asked what it already has
     * before anything is made, and a body is created once or never. Replaying used to
     * recreate whatever the owner's consolidation had just merged away.
     */
    suspend fun push() {
        val a = api ?: return
        val s = ship ?: return
        if (db.orreryAccounts().get(s) == null) return
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
        var callsForRetry: List<((String, String?) -> String) -> Facts> = emptyList()
        var spokenForRetry: List<Heard> = emptyList()
        var confirmed: List<io.nisfeb.talon.data.OrreryNoticedEntity> = emptyList()
        var token: String? = null
        // Model runs the pass has spent: a failure after them buys them
        // again, and only that is worth waiting longer for.
        var ran = 0
        // Put back what this pass took from the queues, for the next one,
        // unless the pipe is off or the queues have moved to another ship.
        suspend fun putBack(c: List<((String, String?) -> String) -> Facts>, t: List<Heard>) = pendingLock.withLock {
            if (_enabled.value && holding(s)) {
                calls.addAll(0, c)
                transcripts.addAll(0, t)
            }
        }
        suspend fun requeue() = putBack(callsForRetry, spokenForRetry)
        // Whether the row this pass read is still the pipe: not turned
        // off, and its key not replaced, while the pass ran.
        suspend fun stillOurs() = db.orreryAccounts().get(s)?.token == token
        try {
            // Inside the try, so a pass cancelled while it waits for the
            // lock still clears [_pushing]. Outside it, the flag stayed
            // set and every later pass returned at once: the pipe went
            // quiet until the process restarted.
            passLock.lock()
            locked = true
            // Under the lock: a key replaced while another pass still
            // sent with the old one got that pass a 403, which turned the
            // pipe off and deleted the row holding the new key.
            db.orreryAccounts().get(s)?.let { ensureScope(s, it) }
            val row = db.orreryAccounts().get(s) ?: return
            token = row.token
            val nowMs = now()
            var facts = Facts()
            // Claims the owner confirmed in the tray. They wait in the
            // table, not in memory, until a pass has put them on the
            // ship: a process killed before the pass used to lose them,
            // after they had already left the tray.
            confirmed = db.orreryNoticed().confirming(s).filter { n ->
                val f = oneItem("claim ${n.id}") { factsOf(n) }
                if (f != null) facts += f else db.orreryNoticed().setState(n.id, "unreadable")
                f != null
            }
            // A call's words, taken here rather than inside the triage, so
            // a pass that fails can put them back: nothing else keeps them.
            val spoken = pendingLock.withLock { if (holding(s)) transcripts.toList().also { transcripts.clear() } else emptyList() }
            spokenForRetry = spoken
            // What the ship has, which is what a client goes by. It is
            // never told what this install remembers.
            // Required: a pass with no view of the ship's bodies made
            // everyone again from their @p, the twins of whoever the owner
            // had merged. No answer here fails the pass, to be tried again.
            val raw = a.stateJson(row.token)
            val view = a.viewOf(raw)
            val sent = db.orrerySent()
            val record = mutableListOf<io.nisfeb.talon.data.OrrerySentEntity>()
            fun remember(key: String, value: String = "") {
                record += io.nisfeb.talon.data.OrrerySentEntity(s, key, value, nowMs)
            }

            val book = book()
            val people = People(a, row.token, sent, s, view.bodies)
            // Calls made since the last pass, their speakers resolved now.
            callsForRetry = pendingLock.withLock { if (holding(s)) calls.toList().also { calls.clear() } else emptyList() }
            callsForRetry.forEach { make -> oneItem("a call") { make(people::idFor) }?.let { facts += it } }
            // Read once: the table holds every peer ever seen, and the
            // triage below wants the same few.
            val known = db.contacts().all().filter { it.ship == s || it.ship in book }
            for (c in known) oneItem("contact ${c.ship}") {
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

            // The ship reads the owner's chats (orrery 39), the calendar's
            // events (orrery 47) and the mail itself: read here too, each
            // was read twice and every fact written twice.
            val triaged = triage(a, spoken, s, nowMs, known, view) { key, value -> remember(key, value) }
            facts += triaged.facts
            ran = triaged.ran
            // Calls the triage did not finish go back once the pass has put
            // what it read of them on the ship, from where it stopped. A
            // pass that fails puts every call back from where it began:
            // resumed at once, the turns it had read were lost with it.


            // Only what the key's schema lists: a row on an attribute the
            // owner has not named sits outside their vocabulary, and one on
            // a kind the key may not see got the whole batch refused with
            // a 403, which read as the key revoked and turned the pipe off.
            view.attrs.takeIf { it.isNotEmpty() }?.let { attrs ->
                val (listed, not) = facts.observations.partition { o -> attrs[o.subject.substringBefore('/')]?.contains(o.attr) == true }
                val (seen, unseen) = facts.bodies.partition { b -> b.id.substringBefore('/') in attrs }
                // A kind the ship has and this key cannot see is the key
                // behind the schema, not the owner's vocabulary: measured
                // again at once, and the pass tried again under a key that
                // sees it, rather than its records written for facts that
                // never went.
                val blind = (not.map { it.subject.substringBefore('/') } + unseen.map { it.id.substringBefore('/') })
                    .filter { it !in attrs }.toSet()
                // Once an hour at most: a re-mint the ship keeps refusing
                // (fifty keys, or more kinds than a key may hold) failed
                // every pass after its model runs, and moved nothing. Past
                // that the facts are dropped, as an unnamed attribute is.
                val lastMeasure = db.orrerySent().get(s, SCOPE_KEY)?.value?.toLongOrNull() ?: 0L
                if (blind.isNotEmpty() && nowMs - lastMeasure > BLIND_RETRY_MS) {
                    val full = runCatching { a.schema() }.getOrNull()?.let(::schemaKinds).orEmpty()
                    if (blind.any { it in full }) {
                        db.orrerySent().forget(s, SCOPE_KEY)
                        scopeChecked = false
                        error("this install's key cannot see ${blind.filter { it in full }.joinToString()}; a key that can is asked for")
                    }
                }
                if (not.isNotEmpty() || unseen.isNotEmpty()) {
                    Log.i(TAG, "${not.size + unseen.size} not written, outside the key's schema: " +
                        (not.map { "${it.subject.substringBefore('/')}.${it.attr}" } + unseen.map { it.id.substringBefore('/') }).distinct().joinToString())
                    facts = facts.copy(observations = listed, bodies = seen)
                }
            }
            var refused = 0
            var firstReason: String? = null
            // The ship answers a bad item in its 200, beside the good ones:
            // a 400 is a batch it cannot read at all, which fails the pass.
            for (batch in batches(facts)) {
                a.observe(batch, row.token).refused.forEach {
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
                // The ship answers a batch before its writer applies it:
                // asked at once, the pass read the state without the facts
                // that called for it, and spent the owner's small cap.
                // ponytail: a pause, not a read back; the writer lands a
                // batch in well under a second.
                delay(2_000)
                runCatching { a.generate(row.token, about) }
                    .onSuccess { Log.i(TAG, "urgent pass: $it (${about.joinToString().ifBlank { "the owner" }})") }
                    .onFailure { Log.i(TAG, "urgent pass not asked for: ${it.message}") }
            }
            // Turned off, or given a new key, while this pass ran: nothing
            // it did is written back. Its cursor write brought back the row
            // turning off had just deleted, and the next launch turned the
            // pipe on again with a revoked key. Checked and written under
            // the lock turning off takes, so it cannot come in between.
            val ours = rowLock.withLock {
                stillOurs().also {
                    if (it) {
                        // Only once the ship has taken them: a pass that
                        // failed halfway must be free to say them again.
                        if (record.isNotEmpty()) sent.putAll(record)
                        confirmed.forEach { n -> db.orreryNoticed().setState(n.id, "confirmed") }
                    }
                }
            }
            if (!ours) {
                requeue()
                return
            }
            // What is open, for the tray and the notifications.
            runCatching { a.actions(row.token, status = "open") }
                .onSuccess { published(it) }
                .onFailure { Log.i(TAG, "actions skipped: ${it.message}") }
            // Before the check: turns of a call nobody read are kept nowhere
            // else, and [putBack] itself drops them if the pipe is off.
            if (triaged.unread.isNotEmpty()) putBack(emptyList(), triaged.unread)
            if (!rowLock.withLock { stillOurs() }) return
            failuresInARow = 0
            _lastPushMs.value = nowMs
            _error.value = listOfNotNull(
                "$refused refused: ${firstReason ?: "no reason given"}".takeIf { refused > 0 },
                "The model did not answer: ${triaged.modelDownWhy ?: "no reason given"}. What it would have read waits for it.".takeIf { triaged.modelDown },
            ).joinToString(" ").ifEmpty { null }
        // What a failed pass does with what it carried: one rule. A key
        // the ship refuses turns the pipe off. Anything else, a busy or
        // updating ship, no answer, one cut off halfway, a batch refused
        // whole, a fault here, is tried again: the calls and transcripts
        // go back on the queue and the claims stay in the table. The loop
        // waits longer each time, unless nothing answered at all and no
        // model runs were spent. A fault in reading one item, or the
        // ship refusing a few items, costs those items alone ([oneItem],
        // [observeSplitting]) and is not here; a model that did not answer
        // holds what it would have read and is not a failure either.
        } catch (e: OrreryError.Refused) {
            _error.value = e.message
            // Only "forbidden" is the key refused. The ship answers 403
            // too for a batch reaching past the key's scope, and for a
            // read-only key, neither of which is the key gone; and for a
            // key it has minted and not stored yet, which is a key not
            // there yet, and was turning the pipe off as it was turned on.
            val fresh = token?.let { t -> db.orreryAccounts().get(s)?.takeIf { it.token == t } }
                ?.let { r -> db.orrerySent().get(s, "minted:${r.clientId}")?.atMs }
                ?.let { now() - it < KEY_GRACE_MS } == true
            if (e.status == 403 && e.reason == "forbidden" && !fresh) {
                // Deleted before anything is cancelled: turning off cancels
                // the loop this pass may run in, and a delete after that
                // never ran, so the next launch turned the pipe on again
                // with the key the ship had just refused. Unless the key
                // has been replaced since.
                val ours = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    rowLock.withLock { stillOurs().also { if (it) db.orreryAccounts().delete(s) } }
                }
                if (ours) {
                    _error.value = "The ship no longer accepts this install's key. Turn the pipe on again to mint a new one."
                    turnOff()
                } else {
                    requeue()
                }
            } else {
                if (ran > 0 || costTheShip(e)) failuresInARow++
                requeue()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // The loop restarted mid-pass: what it took waits for the
            // next one. A detach, a ship switch or turning the pipe off
            // drops it instead ([putBack]).
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { requeue() }
            throw e
        } catch (e: Throwable) {
            // Throwable: an Error here skipped the put back, and the calls
            // it took exist nowhere else. The loop carries on after it.
            _error.value = e.message ?: e::class.simpleName
            if (e !is OrreryError) Log.w(TAG, "pass failed", e)
            if (ran > 0 || costTheShip(e)) failuresInARow++
            requeue()
        } finally {
            if (locked) passLock.unlock()
            _pushing.value = false
        }
    }

    /** A call's words on the queue, and the turn to read from: one the model stopped answering in resumes there. */
    private data class Heard(val address: String, val lines: List<Spoken>, val from: Int = 0)

    /** When a key was minted, for the grace it gets while the ship stores it. */
    private suspend fun minted(s: String, clientId: String) =
        db.orrerySent().put(io.nisfeb.talon.data.OrrerySentEntity(s, "minted:$clientId", "", now()))

    /** Passes in a row that failed; the loop waits longer after each ([backoff]). */
    private var failuresInARow = 0

    /**
     * One item of a pass, a post, a call, a claim, an event: a fault in
     * reading it costs that item, never the pass. A pass that failed on
     * one bad item failed the same way on every pass after, and nothing
     * behind it ever moved. No answer from the ship is not the item's
     * fault: that fails the pass, which is tried again with the item in
     * it. A model that does not answer holds the item instead
     * ([Reading.modelDown]).
     */
    private inline fun <T> oneItem(what: String, block: () -> T): T? = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        if (e is OrreryError || io.nisfeb.talon.util.isTransientNetworkError(e)) throw e
        Log.w(TAG, "$what skipped", e)
        null
    }


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
        // A mint refused is measured all the same, so it is tried again in
        // twelve hours rather than on every pass.
        val minted = runCatching { a.mint("Talon on $platform", by(), schemaKinds(full), schemaActions(full)) }
            .onFailure { Log.w(TAG, "could not mint a key with the whole scope: ${it.message}") }
            .getOrNull() ?: return measured()
        // Waited for, as far as it goes: a key the ship has not stored yet
        // is forbidden, and revoked before it lands it answered 404 and
        // then landed anyway, a full-scope key nobody held, one more on
        // every pass until the ship's fifty were gone. A slower writer is
        // covered by the grace a new key gets ([minted]).
        a.keyLanded(minted.token)
        // Only over the row it measured: turned off meanwhile, the new
        // key is given back rather than the row made again.
        val replaced = rowLock.withLock {
            (db.orreryAccounts().get(s)?.token == row.token).also {
                if (it) {
                    db.orreryAccounts().upsert(row.copy(clientId = minted.id, token = minted.token))
                    minted(s, minted.id)
                }
            }
        }
        if (!replaced) {
            runCatching { a.revoke(minted.id) }
            return
        }
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
        /**
         * The model gave no answer this pass: out of credit, rate
         * limited, down. What it would have read waits for a pass it
         * answers in, as what the model runs did not reach does. Read
         * as having said nothing, it was marked read and never read.
         */
        var modelDown: Boolean = false,
        /** What the model's failure said: an empty balance says so, and the owner can top up. */
        var modelDownWhy: String? = null,
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

    /** The pass reads the state once and hands it down: every read costs the ship a second of its single thread. */
    private suspend fun reading(s: String, view: StateView): Reading {
        val model = readWith ?: cloudModelIfOn() ?: (if (isLocalTriageSupported) LocalModels.best()?.second else null)
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
     * The funnel over the transcripts published since the last pass
     * and the contacts' status lines: the ones go
     * through the rules and, where there is one, the model, against
     * the ship's own bodies. Each claim lands in the tray, or goes
     * straight up when the person has trusted that kind of claim. A
     * claim already noticed is the same row again.
     */
    private suspend fun triage(
        a: OrreryApi,
        /** Calls transcribed since the last pass, taken off the queue by the pass. */
        spoken: List<Heard>,
        s: String,
        nowMs: Long,
        /** The people in the owner's book, as this pass already read them. */
        contacts: List<io.nisfeb.talon.data.ContactEntity>,
        /** The state as this pass read it, so the triage adds no read of its own. */
        view: StateView,
        remember: (String, String) -> Unit,
    ): Triaged {
        // Status lines change when nothing is said, so they are counted
        // in before the pass decides it has nothing to do.
        val lines = contacts.mapNotNull { c -> contactStatus(c)?.let { (line, at) -> Triple(c.ship, line, at) } }
        val read = db.orrerySent().some(s, lines.map { "status:${it.first}" }).associate { it.key to it.value }
        val fresh = lines.filter { (ship, line, _) -> read["status:$ship"] != line.hashCode().toString(16) }
        if (spoken.isEmpty() && fresh.isEmpty()) return Triaged()
        // A phone with a computer on the job leaves the reading to it. The
        // phone's cursor still moves; the computer reads these from its
        // own, which did not. ponytail: a computer that never returns
        // leaves them unread; a second cursor would need a column, and
        // the table is already on testers' phones.
        val yielded = io.nisfeb.talon.ui.isTouchPrimary && standDown?.on?.value == true &&
            runCatching { computerActive(a.clients(), nowMs) }.getOrDefault(false)
        _yielding.value = yielded
        // A call recorded here is this phone's alone: the computer never
        // has its words, so the phone reads those itself.
        if (yielded && spoken.isEmpty()) return Triaged()
        val r = reading(s, view)
        // Yielding, the calls are all this phone reads.
        val readStatus = if (yielded) emptyList() else fresh
        r.remember = remember
        var up = Facts()
        // A call's words, by speaker: each run of one voice is one message.
        // Calls first, and whole: they are few, their words are kept
        // nowhere else, and read after the mail they waited behind all
        // of a busy day's. A long call held to the pass's model runs
        // had its later turns read by the rules alone, and then was gone.
        // A model that stops answering sends the call back, whole.
        val unread = mutableListOf<Heard>()
        for (h in spoken) {
            val said = mergeSpoken(h.lines)
            var i = h.from
            // A turn that faults costs that turn: around the whole loop, it
            // stopped the call there, and the turns after it were lost.
            while (i < said.size && !r.modelDown) {
                oneItem("call ${h.address} turn $i") {
                    val sp = said[i]
                    val before = said.subList(maxOf(0, i - ModelExtractor.CONTEXT_MESSAGES), i).map { it.ship to it.text }
                    up += triageText(r, s, nowMs, sp.text, sp.ship, nowMs, h.address, "$i", "talon-call", "${h.address}#$i", before, budgeted = false)
                }
                if (!r.modelDown) i++
            }
            // Stopped partway, the call goes back from the turn the model
            // did not answer: sent back whole, a call longer than the
            // provider's burst bought its first turns again every pass and
            // never finished.
            if (r.modelDown && i < said.size) unread += h.copy(from = i)
        }
        // A status line, read the way a message is read. Once per line:
        // the digest is of the words, so a line put back says nothing new.
        // One the model has no run or no answer for is left unrecorded,
        // and read on a pass that has one.
        for ((ship, line, at) in readStatus.take(STATUS_PER_PASS)) {
            if (r.modelDown || (r.model != null && r.modelRuns >= MODEL_PER_PASS)) break
            oneItem("status of $ship") {
                up += triageText(
                    r, s, nowMs, line, ship, at.coerceAtMost(nowMs), "contact:$ship", ship,
                    "contacts", "talon://profile/$ship",
                )
            }
            if (r.modelDown) break
            remember("status:$ship", line.hashCode().toString(16))
        }
        tally(s, r.day, nowMs)
        return Triaged(up, r.urgentAbout, unread, ran = r.modelRuns, modelDown = r.modelDown, modelDownWhy = r.modelDownWhy)
    }

    /** What one triage read. */
    private data class Triaged(
        val facts: Facts = Facts(),
        /** The bodies to ask the ship to look at now, or null for the usual wait. */
        val urgentAbout: List<String>? = null,
        /** Calls this pass took and did not read, for the next one. */
        val unread: List<Heard> = emptyList(),
        /** Model runs the pass spent: what a failure after it would buy again. */
        val ran: Int = 0,
        /** The model gave no answer, and what it would have read waits. */
        val modelDown: Boolean = false,
        val modelDownWhy: String? = null,
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
        val ourNick = db.contacts().get(s)?.nickname
        // What Talon already holds and the funnel would read: in scope,
        // free text, not a question. Not a new read of the chats.
        val walked = db.messages().postsBetween(0L, now(), s, limit * 6)
        val picked = walked.asSequence()
            .map { it to StoryCache.textFor(it.id, it.contentJson) }
            .filter { (m, t) -> forTheGate(t) && inScope(m.whom, t, s, ourNick, _chatReader.value?.channels.orEmpty()) }
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
    }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it } // Stop is not a failed check

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
        /** Held to the pass's model runs; a call's turns are not, see [triage]. */
        budgeted: Boolean = true,
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
        // Not once it has stopped answering: the rest of a call or a
        // thread each waited out the model's whole timeout again.
        if (r.model != null && !r.modelDown && worth && (!budgeted || r.modelRuns < MODEL_PER_PASS) && forTheReader(text)) {
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
                ModelExtractor.extract(model, r.index, seen, text, author, atMs, s, r.attrs, r.notes, context, onPlan = { plan = it }, onNoAnswer = { e ->
                    r.modelDown = true
                    r.modelDownWhy = e.message ?: e::class.simpleName
                    // An Error is the runtime: the ladder moves past it next pass.
                    if (e !is Exception) LocalModels.broke(model.rung)
                })
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
     * it may propose taking it off, and this client can know, because
     * the ship signs what it writes from an event with that event.
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
     * The calendar and event this body was written from. The ship's
     * calendar reader signs each fact it writes with its event, as
     * source kind `calendar` and id `<calendar>/<uid>`, or the uid alone
     * where its calendar store does not say which calendar (it does not,
     * today), so the way back is the body's own timeline. The calendar
     * is null where the ship did not say it.
     */
    private suspend fun calendarEventFor(s: String, subject: String): Pair<String?, String>? {
        val a = api ?: return null
        val token = keyToken() ?: return null
        val ref = runCatching { a.observationsOf(subject, token) }.getOrNull()
            ?.firstOrNull { it.sourceKind == "calendar" && it.stands }?.sourceId ?: return null
        val cal = ref.substringBefore('/', "").ifBlank { null }
        val uid = ref.substringAfter('/')
        return if (uid.isBlank()) null else cal to uid
    }

    /**
     * The realized start of the event's occurrence on the same day as
     * [nearMs], off the calendar itself, or null where it has none then.
     * The calendar skips by the exact moment, so a reading of "tonight"
     * that landed on the wrong hour would skip nothing.
     */
    private suspend fun occurrenceOn(s: String, event: Pair<String?, String>, nearMs: Long): Long? {
        val url = shipUrl ?: return null
        val rows = runCatching { CalendarApi(http, url).window(nearMs - DAY_MS, nearMs + DAY_MS).rows }.getOrNull() ?: return null
        val (cal, uid) = event
        return occurrenceNear(rows.filter { (cal == null || it.cal == cal) && it.id == uid }.map { it.l }, nearMs)
    }

    /** The person's word on an action: done, dismissed, or failed with why. */
    suspend fun setAction(id: String, status: String, note: String = ""): Result<Unit> = runCatching {
        val a = api ?: error("Not attached to a ship.")
        val s = ship ?: error("Not attached to a ship.")
        // This install's key where it has one, else the owner's own say.
        val token = db.orreryAccounts().get(s)?.token
        a.transition(token, id, status, note)
        _actions.value = settledActions(_actions.value, id, status)
        // The mirror reads every action and the whole calendar before it
        // makes the todo: seconds on a busy ship, so it runs behind the
        // answer, never in its way. And after the answer has landed: read
        // at once, the list still had the action open, and the screen put
        // back what the owner had just answered.
        scope.launch {
            if (token != null) a.landed(token, id, status) else delay(2_000)
            refreshActions()
        }
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
        _answerProblem.value = null
        val was = _actions.value.firstOrNull { it.id == id }
        _actions.value = settledActions(_actions.value, id, status)
        // Answered here: its notification goes now, not on the next read.
        onActions?.invoke(emptyList(), setOf(id))
        scope.launch {
            setAction(id, status, note).onFailure { e ->
                if (was != null) _actions.value = listOf(was) + _actions.value.filterNot { it.id == id }
                _answerProblem.value = "Orrery did not take that answer: ${e.message ?: "no reason given"}"
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
        runCatching { a.actions(keyToken()) }
            .onSuccess { published(it) }
            .onFailure { Log.i(TAG, "actions skipped: ${it.message}") }
        a.generatorLast()?.let { _generator.value = it }
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

        /**
         * Whether a failure could have cost the ship: an error it answered,
         * or a wait it timed out on while its one thread was busy. A refused
         * connection, or a proxy answering 502 or 503 with no ship behind
         * it, cost it nothing, and waiting longer after those left the pipe
         * idle for hours after a restart. The first waits longer each time;
         * the second keeps the usual ten minutes.
         */
        internal fun costTheShip(e: Throwable): Boolean = when (e) {
            is OrreryError.Refused -> e.status != 502 && e.status != 503
            is OrreryError.Unreachable -> generateSequence(e.cause) { it.cause }.take(5).any {
                it is io.ktor.client.plugins.HttpRequestTimeoutException || it is io.ktor.client.network.sockets.SocketTimeoutException
            }
            else -> !io.nisfeb.talon.util.isTransientNetworkError(e)
        }

        /** The attached pipe, for the places that make a fact and hold no repo. */
        @kotlin.concurrent.Volatile
        private var current: OrreryRepo? = null

        /**
         * A call, whose facts are made inside the next pass: that is where
         * the ship is asked who each speaker is, so a person it keeps under
         * another id is that person and not a twin named from the @p.
         */
        fun noteCall(make: (idFor: (ship: String, name: String?) -> String) -> Facts) = enqueue { calls += make }
        const val PUSH_EVERY_MS = 10L * 60 * 1000
        /** How often what is waiting is read again while attached, as a net under the beacon: one small request. */
        const val ACTIONS_EVERY_MS = 15L * 60 * 1000
        private const val BEACON_PATH = "/grubbery/api/keep/apps/shell.shell/desks/orrery.desk/desk/data/orrery.orrery_app/beacon/rev"
        /** Decision calls the gate check has in flight at once. */
        const val GATE_CHECK_AT_ONCE = 6
        /** How long a new key's forbidden reads as not stored yet, not as revoked. */
        const val KEY_GRACE_MS = 5L * 60 * 1000
        /** How often a pass may fail on a key behind the schema, to have it measured again. */
        const val BLIND_RETRY_MS = 60L * 60 * 1000

        /** How long the loop waits after [failures] failed passes in a row: ten minutes, doubling, to five hours and a bit. */
        fun backoff(failures: Int): Long = PUSH_EVERY_MS shl (failures - 1).coerceIn(0, 5)

        /** Held by a pass writing back and by turning the pipe on or off, so neither lands inside the other. */
        private val rowLock = kotlinx.coroutines.sync.Mutex()
        /** What the ship calls open: the three statuses `?status=open` answers with. */
        val OPEN_STATUSES = setOf("proposed", "approved", "claimed")

        /** When the key's scope was last measured against the ship's schema. */
        private const val SCOPE_KEY = "scope:checked"
        private const val SCOPE_GAP_MS = 12L * 60 * 60 * 1000

        /** One orrery pass at a time in this process, whoever asked for it. */
        private val passLock = kotlinx.coroutines.sync.Mutex()

        /** A claim confirmed in the tray that the ship has not taken yet. */
        const val CONFIRMING = "confirming"

        const val TRUST_AFTER = 3
        // ponytail: a per-pass cap; a per-day budget when a phone needs one.
        const val MODEL_PER_PASS = 20

        /** Status lines read in one pass, so a first run does not spend every model run on them. */
        const val STATUS_PER_PASS = 5
        const val GATE_EXAMPLES = 50

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
         * The person's word on a noticed claim. The row leaves the tray
         * at once and waits, as confirming, for a pass to put it on the
         * ship, which is asked for now where a pipe is on. With no pipe
         * it waits for one: the button used to do nothing then, and a
         * tray left over from a pipe since turned off could not be
         * cleared. It teaches the gate once it is on the ship.
         */
        suspend fun confirm(db: AppDatabase, id: String) {
            db.orreryNoticed().setState(id, CONFIRMING)
            val repo = current?.takeIf { it._enabled.value } ?: return
            repo.scope.launch { repo.push() }
        }

        suspend fun discard(db: AppDatabase, id: String) = db.orreryNoticed().setState(id, "discarded")

        /**
         * The words of a call just transcribed, read on the next pass by
         * speaker. Dropped when the pipe is off, like [noteCall].
         */
        fun noteTranscript(address: String, lines: List<Spoken>) {
            if (lines.isNotEmpty()) enqueue { transcripts += Heard(address, lines) }
        }

        /**
         * Hand something to the live repo for its next pass, asked for
         * at once. Nothing is queued while the pipe is off, since no pass
         * would carry it. What a failed pass does with it is the failure
         * rule in [push]; the queue is memory, and a process that ends
         * takes it along.
         */
        private fun enqueue(add: OrreryRepo.() -> Unit) {
            val repo = current?.takeIf { it._enabled.value } ?: return
            // The ship it was said on, and no other.
            val s = repo.ship ?: return
            repo.scope.launch {
                repo.pendingLock.withLock { if (repo.holding(s)) repo.add() }
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
