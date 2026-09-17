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
    /** The cloud opt-in, where the shell offers one. */
    val cloud: CloudTriage? = null,
) {
    private var cloudModel: LocalModel? = null

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
    /** The analyst's open actions, as of the last pass. */
    private val _actions = MutableStateFlow<List<OrreryAction>>(emptyList())
    val actions: StateFlow<List<OrreryAction>> = _actions.asStateFlow()

    private var api: OrreryApi? = null
    // Coroutines only touch this, so a mutex is the whole of the guard
    // (commonMain has no synchronized: iOS is native).
    private val pending = mutableListOf<Facts>()
    private val transcripts = mutableListOf<Pair<String, List<Spoken>>>()
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

            // Mail and the calendar may be absent on this ship; a source
            // that is not there is skipped, not an error of the pipe.
            var mailCursor = row.mailCursor
            var freshMail: List<io.nisfeb.talon.mail.InboxEntry> = emptyList()
            runCatching { AuspexApi(http, url).inbox(limit = MAIL_PER_PASS) }.onSuccess { page ->
                freshMail = page.threads.filter { it.last > row.mailCursor }
                facts += Facts(observations = freshMail.flatMap { mailFacts(it, s, nowMs) })
                mailCursor = freshMail.maxOfOrNull { it.last } ?: mailCursor
            }.onFailure { Log.i(TAG, "mail skipped: ${it.message}") }
            facts += triage(a, row, posts, s, nowMs, url, freshMail)

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
            runCatching { a.actions(row.token) }.onSuccess { _actions.value = it }.onFailure { Log.i(TAG, "actions skipped: ${it.message}") }
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

    /** What one pass reads with: the ship's bodies, the model if any, and the gate. */
    private class Reading(
        val bodies: List<KnownBody>,
        val index: NameIndex,
        val attrs: Map<String, List<String>>,
        val model: LocalModel?,
        val gate: PatternGate?,
        var modelRuns: Int = 0,
    )

    private suspend fun reading(a: OrreryApi, row: OrreryAccountEntity, s: String): Reading? {
        val view = runCatching { a.state(row.token) }.getOrElse { Log.i(TAG, "state view skipped: ${it.message}"); return null }
        val model = cloudModelIfOn() ?: (if (isLocalTriageSupported) LocalModels.best()?.second else null)
        val emb = embedder
        val gate = if (model != null && emb != null) runCatching {
            PatternGate.build(emb, db.orreryNoticed().snippets(s, "confirmed", GATE_EXAMPLES), db.orreryNoticed().snippets(s, "discarded", GATE_EXAMPLES))
        }.getOrNull() else null
        return Reading(view.bodies, NameIndex(view.bodies), view.attrs, model, gate)
    }

    /**
     * The funnel over this pass's messages, the transcripts published
     * since the last, and the mail that arrived: the ones in scope go
     * through the rules and, where there is one, the model, against
     * the ship's own bodies. Each claim lands in the tray, or goes
     * straight up when the person has trusted that kind of claim. A
     * claim already noticed is the same row again.
     */
    private suspend fun triage(a: OrreryApi, row: OrreryAccountEntity, posts: List<io.nisfeb.talon.data.MessageEntity>, s: String, nowMs: Long, url: String, freshMail: List<io.nisfeb.talon.mail.InboxEntry>): Facts {
        val spoken = pendingLock.withLock { transcripts.toList().also { transcripts.clear() } }
        if (posts.isEmpty() && spoken.isEmpty() && freshMail.isEmpty()) return Facts()
        val r = reading(a, row, s) ?: return Facts()
        val allowed = db.orreryChannels().all().toSet()
        val ourNick = db.contacts().get(s)?.nickname
        var up = Facts()
        for (m in posts) {
            val text = StoryCache.textFor(m.id, m.contentJson)
            if (!inScope(m.whom, text, s, ourNick, allowed)) continue
            val kind = if (m.whom.startsWith("~") || m.whom.startsWith("0v")) "talon-dm" else "talon-chat"
            up += triageText(r, s, nowMs, text, m.author, m.sentMs, m.whom, m.id, kind, "talon://chat/${m.whom}?id=${m.id}")
        }
        // A call's words, by speaker: each run of one voice is one message.
        for ((address, lines) in spoken) {
            mergeSpoken(lines).forEachIndexed { i, sp ->
                up += triageText(r, s, nowMs, sp.text, sp.ship, nowMs, address, "$i", "talon-call", "$address#$i")
            }
        }
        // Mail is addressed to us, so every message in a fresh thread is in scope.
        for (e in freshMail.take(MAIL_THREADS_PER_PASS)) {
            val thread = runCatching { AuspexApi(http, url).thread(e.id) }.getOrNull() ?: continue
            for (msg in thread.messages) {
                if (msg.from == s || msg.body.isBlank()) continue
                up += triageText(r, s, nowMs, msg.body, msg.from, msg.sent.coerceAtMost(nowMs), "mail:${e.id}", msg.id, "mail", "talon://mail/${e.id}")
            }
        }
        return up
    }

    /** One text through the rules and the model; what it claims goes to the tray or up. */
    private suspend fun triageText(r: Reading, s: String, nowMs: Long, text: String, author: String, atMs: Long, whom: String, postId: String, kind: String, sourceId: String): Facts {
        var up = Facts()
        // The rules first, then the model where there is one: the same
        // claim from both is one row, and the rules got there.
        val byRules = ruleFacts(text, author, atMs, s, r.index)
        val emb = embedder
        // The gate, once the person has taught it: a message that reads
        // like what they discard does not spend a model run.
        val worth = r.gate == null || emb == null ||
            (runCatching { emb.embed(text) }.getOrNull()?.let { r.gate.worthAModel(it) } ?: true)
        val byModel = if (r.model != null && worth && r.modelRuns < MODEL_PER_PASS && text.length >= 8) {
            r.modelRuns++
            ModelExtractor.extract(r.model, r.index, r.bodies, text, author, atMs, s, r.attrs)
        } else emptyList()
        for (n in byRules + byModel) {
            val trusted = trusted(s, n.attr)
            val entity = OrreryNoticedEntity(
                id = noticedId(sourceId, n.subject, n.attr), ship = s, subject = n.subject, attr = n.attr,
                valueJson = n.value.toString(), atMs = n.atMs, untilMs = n.untilMs, conf = n.conf,
                sourceKind = kind, sourceId = sourceId, bodyJson = n.body?.toJson()?.toString(),
                whom = whom, postId = postId, snippet = text.take(200),
                state = if (trusted) "confirmed" else "pending", createdMs = nowMs,
            )
            if (db.orreryNoticed().insertIfNew(entity) != -1L && trusted) up += factsOf(entity)
        }
        return up
    }

    /** The person's word on an action: done, dismissed, or failed with why. */
    suspend fun setAction(id: String, status: String, note: String = ""): Result<Unit> = runCatching {
        val a = api ?: error("Not attached to a ship.")
        val s = ship ?: error("Not attached to a ship.")
        val row = db.orreryAccounts().get(s) ?: error("The pipe is off.")
        a.transition(row.token, id, status, note)
        _actions.value = _actions.value.filterNot { it.id == id }
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
            val repo = current ?: return false
            if (!repo._enabled.value) return false
            db.orreryNoticed().setState(id, "confirmed")
            note(factsOf(n))
            return true
        }

        suspend fun discard(db: AppDatabase, id: String) = db.orreryNoticed().setState(id, "discarded")

        /**
         * The words of a call just transcribed, read on the next pass by
         * speaker. Dropped when the pipe is off, like [note].
         */
        fun noteTranscript(address: String, lines: List<Spoken>) {
            val repo = current ?: return
            if (!repo._enabled.value || lines.isEmpty()) return
            repo.scope.launch {
                repo.pendingLock.withLock { repo.transcripts += address to lines }
                repo.push()
            }
        }
    }
}

/** The key's id is the part of the token before the dot; the ship answers it separately too. */
private fun MintedKey.clientId(): String = id
