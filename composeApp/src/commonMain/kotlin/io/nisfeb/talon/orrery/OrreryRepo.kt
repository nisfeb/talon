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
    /**
     * The ships in the person's own contacts book. Bodies are made for
     * these and for nobody else the ship has merely heard of: the
     * contacts table holds every peer ever seen, which is thousands.
     */
    private val book: () -> Set<String> = { emptySet() },
    /** On a phone, whether to leave the reading to a computer that has read lately. */
    val standDown: StandDown? = null,
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
    /** True while this phone is leaving the reading to a computer. */
    private val _yielding = MutableStateFlow(false)
    val yielding: StateFlow<Boolean> = _yielding.asStateFlow()

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
        // A key minted now already sees activities.
        db.orrerySent().put(io.nisfeb.talon.data.OrrerySentEntity(ship ?: "", SCOPE_KEY, "activity", now()))
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
        db.orrerySent().clear(s)
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
            // What the ship has, which is what a client goes by. It is
            // never told what this install remembers.
            val view = runCatching { a.state(row.token) }.getOrNull()
            val sent = db.orrerySent()
            val record = mutableListOf<io.nisfeb.talon.data.OrrerySentEntity>()
            fun remember(key: String, value: String = "") {
                record += io.nisfeb.talon.data.OrrerySentEntity(s, key, value, nowMs)
            }

            val book = book()
            val people = People(a, row.token, sent, s, view?.bodies.orEmpty())
            for (c in db.contacts().all().filter { it.ship == s || it.ship in book }) {
                val handle = shipHandle(c.ship)
                val id = people.idFor(c.ship, c.nickname ?: handle)
                val body = personBody(c, id, handle, shipHandleLong(c.ship))
                // The body only when the ship has no such person, or when
                // what it goes by has actually changed.
                val digest = bodyDigest(body)
                val known = sent.get(s, "person:${c.ship}")?.value
                val fresh = known != "$id|$digest"
                if (fresh) remember("person:${c.ship}", "$id|$digest")
                facts += Facts(
                    bodies = if (fresh && !people.shipHasBody(id)) listOf(body) else emptyList(),
                    observations = contactStatus(c, id),
                )
            }

            val posts = db.messages().postsAfter(row.messagesCursor, s, MESSAGES_PER_PASS)
            // Contact from a DM is contact with you. In a channel it is only
            // worth recording when the author is already in your book.
            val direct = posts.filter { it.whom.startsWith("~") || it.whom.startsWith("0v") || it.author in book }
            facts += Facts(observations = direct.mapNotNull { m -> messageFacts(m, s, people.idFor(m.author, null)) })
            val messagesCursor = posts.maxOfOrNull { it.sentMs } ?: row.messagesCursor

            // Mail and the calendar may be absent on this ship; a source
            // that is not there is skipped, not an error of the pipe.
            var mailCursor = row.mailCursor
            var freshMail: List<io.nisfeb.talon.mail.InboxEntry> = emptyList()
            runCatching { AuspexApi(http, url).inbox(limit = MAIL_PER_PASS) }.onSuccess { page ->
                freshMail = page.threads.filter { it.last > row.mailCursor }
                facts += Facts(
                    observations = freshMail.flatMap { e ->
                        mailFacts(e, s, nowMs) { ship -> people.idFor(ship, null) }
                    },
                )
                mailCursor = freshMail.maxOfOrNull { it.last } ?: mailCursor
            }.onFailure { Log.i(TAG, "mail skipped: ${it.message}") }
            facts += triage(a, row, posts, s, nowMs, url, freshMail) { key -> remember(key) }

            runCatching { CalendarApi(http, url).window(nowMs - BACKFILL_MS, nowMs + AHEAD_MS) }.onSuccess { w ->
                // The calendars this ship keeps itself. An event on one
                // another ship shares is not ours to name an organizer for.
                val ourCalendars = runCatching {
                    CalendarApi(http, url).calendars().filter { it.kind == "local" }.map { it.id }.toSet()
                }.getOrDefault(emptySet())
                for (subject in calendarSubjects(w.rows)) {
                    // The body decided for this event, and the event as
                    // it was when that decision was made.
                    val mark = sent.get(s, subject.key)?.value?.takeIf { it.isNotBlank() }
                    val decided = mark?.substringBefore('|')
                    val digest = subject.digest
                    val seen = sent.under(s, "occ:${subject.cal}/${subject.uid}/")
                        .associate { it.key to it.value }
                    // An occurrence the calendar no longer has at a time
                    // this install can still see: moved, or called off.
                    val dropped = if (decided == null) emptySet() else retractMoved(a, row.token, decided, subject, seen, s, nowMs)
                    // A new time, place or description means what the
                    // ship was told no longer describes the event.
                    val changed = mark != null && (mark.substringAfter('|', "") != digest || dropped.isNotEmpty())
                    // Ask the ship before making anything: by the title it
                    // goes by, then by the calendar's own id, which
                    // reconcile keeps as an alias of the activity it built.
                    val hits = if (decided != null) emptyList() else buildList {
                        addAll(runCatching { a.resolve(subject.title, row.token) }.getOrDefault(emptyList()))
                        if (none { it.isExact }) {
                            addAll(runCatching { a.resolve(subject.uid, row.token) }.getOrDefault(emptyList()))
                        }
                    }
                    val write = calendarWrite(
                        subject, decided, hits, seen.keys - dropped, s, nowMs, changed, subject.cal in ourCalendars,
                    )
                    facts += write.facts
                    if (mark != "${write.bodyId}|$digest") remember(subject.key, "${write.bodyId}|$digest")
                    write.occurrences.forEach { (key, end) -> remember(key, end.toString()) }
                }
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
            // Only once the ship has taken them: a pass that failed
            // halfway must be free to say the same things again.
            if (record.isNotEmpty()) sent.putAll(record)
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

    /**
     * The first keys this install minted were made before orrery had an
     * activity kind, so they cannot see one: resolve would answer
     * nothing for a recurring event and the pass would make the twin it
     * was told not to. A key without it is replaced, once.
     */
    private suspend fun ensureScope(s: String, row: OrreryAccountEntity) {
        val a = api ?: return
        if (db.orrerySent().get(s, SCOPE_KEY) != null) return
        val minted = runCatching { a.mint("Talon on $platform", by()) }
            .onFailure { Log.w(TAG, "could not mint a key that sees activities: ${it.message}") }
            .getOrNull() ?: return
        db.orreryAccounts().upsert(row.copy(clientId = minted.id, token = minted.token))
        runCatching { a.revoke(row.clientId) }
        db.orrerySent().put(io.nisfeb.talon.data.OrrerySentEntity(s, SCOPE_KEY, "activity", now()))
        Log.i(TAG, "replaced this install's key with one that can see activities")
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
        private val known: Set<String> = bodies.map { it.id }.toSet()
        private val names: List<Pair<String, String>> = bodies.filter { it.id.startsWith("person/") }.map { (it.name ?: "") to it.id }
        private val decided = mutableMapOf<String, String>()

        fun shipHasBody(id: String): Boolean = id in known

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
        return Reading(view.bodies, NameIndex(view.bodies), view.attrs, view.notes, model, gate)
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
        s: String,
        nowMs: Long,
        url: String,
        freshMail: List<io.nisfeb.talon.mail.InboxEntry>,
        remember: (String) -> Unit,
    ): Facts {
        val spoken = pendingLock.withLock { transcripts.toList().also { transcripts.clear() } }
        if (posts.isEmpty() && spoken.isEmpty() && freshMail.isEmpty()) return Facts()
        // A phone with a computer on the job leaves the reading to it. The
        // phone's cursor still moves; the computer reads these from its
        // own, which did not. ponytail: a computer that never returns
        // leaves them unread; a second cursor would need a column, and
        // the table is already on testers' phones.
        if (io.nisfeb.talon.ui.isTouchPrimary && standDown?.on?.value == true) {
            val yielded = runCatching { computerActive(a.clients(), nowMs) }.getOrDefault(false)
            _yielding.value = yielded
            if (yielded) return Facts()
        } else {
            _yielding.value = false
        }
        val r = reading(a, row, s) ?: return Facts()
        val allowed = db.orreryChannels().all().toSet()
        val ourNick = db.contacts().get(s)?.nickname
        var up = Facts()
        // A message is read once. The ship answers "existing" for an
        // observation it already holds, but the model costs a second
        // every time and its answer is not guaranteed to be the same.
        val handled = db.orrerySent().some(s, posts.map { "msg:${it.whom}/${it.id}" }).map { it.key }.toSet()
        for (m in posts) {
            val key = "msg:${m.whom}/${m.id}"
            if (key in handled) continue
            val text = StoryCache.textFor(m.id, m.contentJson)
            remember(key)
            if (!inScope(m.whom, text, s, ourNick, allowed)) continue
            val kind = if (m.whom.startsWith("~") || m.whom.startsWith("0v")) "talon-dm" else "talon-chat"
            // A message is read with the ones before it: "yes, at 8"
            // says nothing alone. They are for reading only, and the
            // claims are held to the words of this one.
            val before = db.messages().before(m.whom, m.sentMs, ModelExtractor.CONTEXT_MESSAGES).reversed()
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
        val byModel = if (r.model != null && worth && r.modelRuns < MODEL_PER_PASS && text.length >= 8) {
            r.modelRuns++
            ModelExtractor.extract(r.model, r.index, r.bodies, text, author, atMs, s, r.attrs, r.notes, context)
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
        private const val SCOPE_KEY = "scope:activity"
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
