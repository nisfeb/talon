package io.nisfeb.talon.orrery

import io.ktor.client.HttpClient
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
import io.nisfeb.talon.util.nowMs

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
) {
    // Writes under the key ride a client with no cookie: with both on
    // one request the ship would take the cookie and write as the owner.
    private val bare: HttpClient by lazy { createAppHttpClient() }

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

    private var api: OrreryApi? = null
    // Coroutines only touch this, so a mutex is the whole of the guard
    // (commonMain has no synchronized: iOS is native).
    private val pending = mutableListOf<Facts>()
    private val pendingLock = Mutex()
    private var shipUrl: String? = null
    private var ship: String? = null
    private var loop: Job? = null

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
            runCatching { refreshModel() }
        }
    }

    fun detach() {
        if (current === this) current = null
        loop?.cancel()
        loop = null
        api = null
        shipUrl = null
        ship = null
        _availability.value = OrreryAvailability.UNKNOWN
        _enabled.value = false
        _error.value = null
    }

    suspend fun probe() {
        val a = api ?: return
        runCatching { a.probe() }
            .onSuccess { _availability.value = it; _error.value = null }
            .onFailure { _availability.value = OrreryAvailability.UNKNOWN; _error.value = it.message }
    }

    /** Mint this install's key and start the walk. */
    suspend fun enable(): Result<Unit> = runCatching {
        val a = api ?: error("Not attached to a ship.")
        val s = ship ?: error("Not attached to a ship.")
        val key = a.mint("Talon on $platform", by())
        val start = now() - BACKFILL_MS
        db.orreryAccounts().upsert(OrreryAccountEntity(s, key.clientId(), key.token, start, start, 0))
        _enabled.value = true
        _error.value = null
        startLoop()
    }

    /** Revoke the key on the ship and forget it here. */
    suspend fun disable(): Result<Unit> = runCatching {
        val s = ship ?: return@runCatching
        loop?.cancel()
        loop = null
        val row = db.orreryAccounts().get(s)
        if (row != null) {
            // A key the ship has already dropped answers 404; that is
            // the state we want, not a failure to report.
            runCatching { api?.revoke(row.clientId) }
                .onFailure { if (it !is OrreryError.Refused || it.status != 404) throw it }
            db.orreryAccounts().delete(s)
        }
        _enabled.value = false
        _error.value = null
    }

    private fun startLoop() {
        loop?.cancel()
        loop = scope.launch {
            while (isActive) {
                push()
                delay(PUSH_EVERY_MS)
            }
        }
    }

    /** One pass over every source from its cursor. Safe to call any time. */
    suspend fun push() {
        val a = api ?: return
        val s = ship ?: return
        val url = shipUrl ?: return
        val row = db.orreryAccounts().get(s) ?: return
        if (_pushing.value) return
        _pushing.value = true
        var queuedForRetry: List<Facts> = emptyList()
        try {
            val nowMs = now()
            var facts = Facts()
            val queued = pendingLock.withLock { pending.toList().also { pending.clear() } }
            queued.forEach { facts += it }
            queuedForRetry = queued

            db.contacts().all().forEach { c ->
                facts += contactFacts(c, s, shipHandle(c.ship), shipHandleLong(c.ship))
            }

            val posts = db.messages().postsAfter(row.messagesCursor, s, MESSAGES_PER_PASS)
            facts += Facts(observations = posts.mapNotNull { messageFacts(it, s) })
            val messagesCursor = posts.maxOfOrNull { it.sentMs } ?: row.messagesCursor
            facts += triage(a, row, posts, s, nowMs)

            // Mail and the calendar may be absent on this ship; a source
            // that is not there is skipped, not an error of the pipe.
            var mailCursor = row.mailCursor
            runCatching { AuspexApi(http, url).inbox(limit = MAIL_PER_PASS) }.onSuccess { page ->
                val fresh = page.threads.filter { it.last > row.mailCursor }
                facts += Facts(observations = fresh.flatMap { mailFacts(it, s, nowMs) })
                mailCursor = fresh.maxOfOrNull { it.last } ?: mailCursor
            }.onFailure { Log.i(TAG, "mail skipped: ${it.message}") }

            runCatching { CalendarApi(http, url).window(nowMs - BACKFILL_MS, nowMs + AHEAD_MS) }.onSuccess { w ->
                w.rows.forEach { facts += eventFacts(it, s) }
            }.onFailure { Log.i(TAG, "calendar skipped: ${it.message}") }

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
            db.orreryAccounts().upsert(row.copy(messagesCursor = messagesCursor, mailCursor = mailCursor, calendarCursor = nowMs))
            _lastPushMs.value = nowMs
            _error.value = if (refused == 0) null else "$refused refused: ${firstReason ?: "no reason given"}"
        } catch (e: OrreryError.Refused) {
            if (e.status == 403) {
                // The ship no longer takes this install's key: stop, and say so.
                loop?.cancel()
                loop = null
                db.orreryAccounts().delete(s)
                _enabled.value = false
                _error.value = "The ship no longer accepts this install's key. Turn the pipe on again to mint a new one."
            } else {
                _error.value = e.message
            }
        } catch (e: OrreryError) {
            _error.value = e.message
            pendingLock.withLock { pending.addAll(0, queuedForRetry) }
        } finally {
            _pushing.value = false
        }
    }

    /**
     * The funnel over this pass's messages: the ones in scope go through
     * the rules against the ship's own bodies, and each claim lands in
     * the tray, or goes straight up when the person has trusted that
     * kind of claim. A claim already noticed is the same row again.
     */
    private suspend fun triage(a: OrreryApi, row: OrreryAccountEntity, posts: List<io.nisfeb.talon.data.MessageEntity>, s: String, nowMs: Long): Facts {
        if (posts.isEmpty()) return Facts()
        val view = runCatching { a.state(row.token) }.getOrElse { Log.i(TAG, "state view skipped: ${it.message}"); return Facts() }
        val bodies = view.bodies
        val index = NameIndex(bodies)
        val allowed = db.orreryChannels().all().toSet()
        val ourNick = db.contacts().get(s)?.nickname
        val model = if (isLocalTriageSupported) LocalModels.best()?.second else null
        val emb = embedder
        val gate = if (model != null && emb != null) runCatching {
            PatternGate.build(emb, db.orreryNoticed().snippets(s, "confirmed", GATE_EXAMPLES), db.orreryNoticed().snippets(s, "discarded", GATE_EXAMPLES))
        }.getOrNull() else null
        var modelRuns = 0
        var up = Facts()
        for (m in posts) {
            val text = StoryCache.textFor(m.id, m.contentJson)
            if (!inScope(m.whom, text, s, ourNick, allowed)) continue
            val kind = if (m.whom.startsWith("~") || m.whom.startsWith("0v")) "talon-dm" else "talon-chat"
            val sourceId = "talon://chat/${m.whom}?id=${m.id}"
            // The rules first, then the model where there is one: the
            // same claim from both is one row, and the rules got there.
            val byRules = ruleFacts(text, m.author, m.sentMs, s, index)
            // The gate, once the person has taught it: a message that
            // reads like what they discard does not spend a model run.
            val worth = gate == null || emb == null ||
                (runCatching { emb.embed(text) }.getOrNull()?.let { gate.worthAModel(it) } ?: true)
            val byModel = if (model != null && worth && modelRuns < MODEL_PER_PASS && text.length >= 8) {
                modelRuns++
                ModelExtractor.extract(model, index, bodies, text, m.author, m.sentMs, s, view.attrs)
            } else emptyList()
            for (n in byRules + byModel) {
                val trusted = trusted(s, n.attr)
                val entity = OrreryNoticedEntity(
                    id = noticedId(sourceId, n.subject, n.attr), ship = s, subject = n.subject, attr = n.attr,
                    valueJson = n.value.toString(), atMs = n.atMs, untilMs = n.untilMs, conf = n.conf,
                    sourceKind = kind, sourceId = sourceId, bodyJson = n.body?.toJson()?.toString(),
                    whom = m.whom, postId = m.id, snippet = text.take(200),
                    state = if (trusted) "confirmed" else "pending", createdMs = nowMs,
                )
                if (db.orreryNoticed().insertIfNew(entity) != -1L && trusted) up += factsOf(entity)
            }
        }
        return up
    }

    /** Where the ladder stands on this device, for Settings. */
    suspend fun refreshModel() {
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
        fun note(facts: Facts) {
            val repo = current ?: return
            if (!repo._enabled.value) return
            repo.scope.launch {
                repo.pendingLock.withLock { repo.pending += facts }
                repo.push()
            }
        }
        // ponytail: fixed window; a setting when somebody asks for one.
        const val BACKFILL_MS = 30L * 24 * 60 * 60 * 1000
        const val AHEAD_MS = 90L * 24 * 60 * 60 * 1000
        const val PUSH_EVERY_MS = 10L * 60 * 1000
        const val MESSAGES_PER_PASS = 2000
        const val MAIL_PER_PASS = 200
        const val TRUST_AFTER = 3
        // ponytail: a per-pass cap; a per-day budget when a phone needs one.
        const val MODEL_PER_PASS = 20
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
         * The person's word on a noticed claim. Confirming sends it up
         * through the attached pipe; false means there is none to send
         * it through, and the row stays pending for when there is.
         */
        suspend fun confirm(db: AppDatabase, id: String): Boolean {
            val n = db.orreryNoticed().get(id) ?: return false
            val repo = current ?: return false
            if (!repo._enabled.value) return false
            db.orreryNoticed().setState(id, "confirmed")
            note(factsOf(n))
            return true
        }

        suspend fun discard(db: AppDatabase, id: String) = db.orreryNoticed().setState(id, "discarded")
    }
}

/** The key's id is the part of the token before the dot; the ship answers it separately too. */
private fun MintedKey.clientId(): String = id
