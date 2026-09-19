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
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime

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
    // The pass and an answer can both reach the mirror; one at a time,
    // or both see no todo and each make one.
    private val mirrorLock = Mutex()
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
            if (_availability.value == OrreryAvailability.PRESENT) refreshActions()
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
        scopeChecked = false
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
        // Everything the ship has; the lists are only a fallback.
        val full = runCatching { a.schema() }.getOrNull()
        val key = a.mint(
            "Talon on $platform", by(),
            full?.let(::schemaKinds) ?: OrreryApi.KINDS,
            full?.let(::schemaActions) ?: OrreryApi.ACTIONS,
        )
        scopeChecked = full != null
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
    private suspend fun brief(a: OrreryApi, token: String, s: String, url: String, nowMs: Long) {
        val mail = AuspexApi(http, url)
        val state = a.stateJson(token)
        val zone = Brief.zone(state)
        briefZone = zone
        runCatching { answerReplies(a, token, s, mail, state, zone, nowMs) }
            .onFailure { Log.w(TAG, "replies to the brief skipped: ${it.message}") }
        val day = Brief.dueDay(nowMs, zone, briefGrace) ?: return
        val sent = db.orrerySent()
        if (sent.get(s, "brief:$day") != null) return
        // Another install may have sent today's; the ship's mail says so.
        // ponytail: two computers waking at seven can still both send;
        // the check again below narrows it to the seconds of one send.
        suspend fun sentElsewhere(): Boolean {
            val there = mail.inbox(io.nisfeb.talon.mail.MailView.ALL, limit = 50).threads.any { Brief.dayOf(it.subject) == day }
            if (there) sent.put(io.nisfeb.talon.data.OrrerySentEntity(s, "brief:$day", "", nowMs))
            return there
        }
        if (sentElsewhere()) return
        val frontier = cloud?.config?.invoke()?.takeIf { it.apiKey.isNotBlank() }
            ?: run { Log.i(TAG, "brief not sent: no frontier model is set under AI"); return }
        // One install writes the brief, and it holds the day's lease
        // before anything costs money. A holder that goes quiet for
        // twenty minutes, longer than a model call, can be taken over.
        val lease = claim ?: run { Log.i(TAG, "brief not sent: no way to coordinate with other installs here"); return }
        if (!lease(BRIEF_LEASE, Brief.LEASE_STALE_MS, Brief.LEASE_SETTLE_MS)) {
            Log.i(TAG, "brief left to the install holding today's lease")
            return
        }
        val cal = CalendarApi(http, url)
        val from = day.atStartOfDayIn(zone).toEpochMilliseconds()
        val events = cal.window(from, from + 26 * 3_600_000L).rows
        val todos = cal.tasks()
        val actions = a.actions(token, status = "all")
        val today = Brief.today(day, zone, events, todos, state)
        val (waiting, tags) = Brief.waiting(actions, zone, Brief.names(state))
        val decided = actions.filter { it.status in setOf("done", "dismissed", "failed") }.sortedBy { it.id }
        val suggestions = io.nisfeb.talon.ai.AiClient { frontier }.complete(
            Brief.SYSTEM,
            Brief.statePrompt(state, decided, isoUtc(nowMs), zone, today, waiting),
            maxOutputTokens = 4000,
            timeoutMs = 180_000,
        )
        // Again: the model took its time, and another install may have finished first.
        if (sentElsewhere()) return
        mail.send(listOf(s), Brief.subject(day), Brief.render(day, today, waiting, suggestions))
        // The tags go with the day: only the install that sent a brief
        // knows which action each one names, so only it answers replies.
        val tagJson = kotlinx.serialization.json.buildJsonObject { tags.forEach { (t, id) -> put(t, kotlinx.serialization.json.JsonPrimitive(id)) } }
        sent.put(io.nisfeb.talon.data.OrrerySentEntity(s, "brief:$day", tagJson.toString(), nowMs))
        Log.i(TAG, "brief for $day sent, ${tags.size} waiting")
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
    ) {
        val sent = db.orrerySent()
        val threads = mail.inbox(io.nisfeb.talon.mail.MailView.ALL, limit = 50).threads
            .filter { it.count > 1 && Brief.dayOf(it.subject) != null }
        for (entry in threads) {
            val tagsRaw = sent.get(s, "brief:${Brief.dayOf(entry.subject)}")?.value?.takeIf { it.isNotBlank() } ?: continue
            val tags = Json.parseToJsonElement(tagsRaw).jsonObject.mapValues { it.value.jsonPrimitive.content }
            val thread = mail.thread(entry.id) ?: continue
            val brief = Brief.briefOf(thread, s) ?: continue
            val handled = sent.some(s, thread.messages.map { "reply:${it.id}" }).map { it.key.removePrefix("reply:") }.toSet()
            for (reply in Brief.pendingReplies(thread, s, handled)) {
                val words = Brief.ownWords(reply.body, brief.body)
                if (words.isNotBlank()) answer(a, token, state, zone, nowMs, reply, words, tags)
                // Only once all of it is written: a reply that failed
                // halfway is read again, and every write is one the ship
                // answers as existing or refuses as already done.
                sent.put(io.nisfeb.talon.data.OrrerySentEntity(s, "reply:${reply.id}", "", nowMs))
            }
        }
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
        val frontier = cloud?.config?.invoke()?.takeIf { it.apiKey.isNotBlank() }
            ?: error("no frontier model is set under AI")
        val at = reply.sent.takeIf { it > 0 } ?: nowMs
        val byId = a.actions(token, status = "all").associateBy { it.id }
        val tagged = tags.mapNotNull { (t, id) -> byId[id]?.let { t to it } }.toMap()
        val view = a.viewOf(state)
        // ponytail: an answer that is not JSON throws and the reply is
        // asked again next pass; a model that keeps failing keeps costing.
        val answer = Brief.parseAnswer(
            io.nisfeb.talon.ai.AiClient { frontier }.complete(
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
        val asked = Brief.replyActions(answer, state["schema"] as? JsonObject, known)
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
        val note = "from the owner's reply to the brief"
        var id = old.id
        var status = old.status
        if (d.dueMs != null || d.about != null) {
            runCatching { a.transition(token, old.id, "dismissed", "replaced, $note") }
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
     * An orrery task is a todo in the calendar, so the person sees it
     * where they see the rest of what they have to do. The link is kept
     * in the todo, never here, which is what makes a pass that starts
     * from nothing safe.
     */
    private suspend fun mirrorTasks(a: OrreryApi, token: String?, url: String) = mirrorLock.withLock {
        val actions = a.actions(token, status = "all")
        if (actions.none { it.kind == "task" }) return@withLock
        val cal = CalendarApi(http, url)
        val moves = taskMoves(actions, cal.tasks())
        if (moves.isEmpty()) return@withLock
        val ball = cal.config().ball.takeIf { it.isNotBlank() } ?: return@withLock
        for (m in moves) {
            when (m) {
                is TaskMove.Make -> cal.poke(ball, todoBody(m.action))
                is TaskMove.Tick -> cal.poke(ball, io.nisfeb.talon.calendar.doneBody(m.todoId, true))
                is TaskMove.Drop -> cal.poke(ball, io.nisfeb.talon.calendar.deleteBody(m.todoId))
                // The owner ticked it where they saw it. If an executor
                // holds the claim the ship refuses, and the next pass
                // finds it still ticked and says so again.
                is TaskMove.Report -> a.transition(token, m.actionId, "done", "ticked in the calendar")
            }
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
            val forgets = mutableListOf<String>()
            fun remember(key: String, value: String = "") {
                record += io.nisfeb.talon.data.OrrerySentEntity(s, key, value, nowMs)
            }

            val book = book()
            val people = People(a, row.token, sent, s, view?.bodies.orEmpty())
            for (c in db.contacts().all().filter { it.ship == s || it.ship in book }) {
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
            facts += triage(a, row, posts, s, nowMs, url, freshMail, book) { key, value -> remember(key, value) }

            runCatching { CalendarApi(http, url).window(nowMs - BACKFILL_MS, nowMs + AHEAD_MS) }.onSuccess { w ->
                // The calendars this ship keeps itself. An event on one
                // another ship shares is not ours to name an organizer for.
                val calendars = runCatching { CalendarApi(http, url).calendars() }.getOrDefault(emptyList())
                val ourCalendars = calendars.filter { it.kind == "local" }.map { it.id }.toSet()
                // Who the ship keeps, so a name in a title lands on the
                // person it already has.
                val cast = view?.let { EventPeople.of(it.bodies) } ?: EventPeople.NONE
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
                        subject, decided, hits, seen.keys - dropped, s, nowMs, changed,
                        subject.cal in ourCalendars, cast,
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
                runCatching { CalendarApi(http, url).events() }.onSuccess { all ->
                    // An empty listing is likelier a hiccup than every
                    // event deleted at once, and a cancel is not undone.
                    if (all.isEmpty()) return@onSuccess
                    val gone = vanishedEvents(
                        written = sent.under(s, "cal:").associate { it.key to it.value },
                        occurrences = sent.under(s, "occ:").associate { it.key to it.value },
                        kept = all.map { "cal:${it.cal}/${it.id}" }.toSet(),
                        calendars = calendars.map { it.id }.toSet(),
                        nowMs = nowMs,
                    )
                    facts += gone.facts
                    forgets += gone.forget
                }.onFailure { Log.i(TAG, "vanished events skipped: ${it.message}") }
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
            forgets.forEach { sent.forget(s, it) }
            db.orreryAccounts().upsert(row.copy(messagesCursor = messagesCursor, mailCursor = mailCursor, calendarCursor = nowMs))
            runCatching { a.actions(row.token) }.onSuccess { _actions.value = it }.onFailure { Log.i(TAG, "actions skipped: ${it.message}") }
            runCatching { mirrorTasks(a, row.token, url) }.onFailure { Log.i(TAG, "tasks skipped: ${it.message}") }
            runCatching { brief(a, row.token, s, url, nowMs) }.onFailure { Log.w(TAG, "brief not sent: ${it.message}") }
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
        val full = runCatching { a.schema() }.getOrNull() ?: return
        val mine = runCatching { a.stateJson(row.token)["schema"] as? JsonObject }.getOrNull() ?: return
        if (scopeCovers(mine, full)) {
            scopeChecked = true
            return
        }
        val minted = runCatching { a.mint("Talon on $platform", by(), schemaKinds(full), schemaActions(full)) }
            .onFailure { Log.w(TAG, "could not mint a key with the whole scope: ${it.message}") }
            .getOrNull() ?: return
        db.orreryAccounts().upsert(row.copy(clientId = minted.id, token = minted.token))
        runCatching { a.revoke(row.clientId) }
        scopeChecked = true
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
        private val known: Set<String> = bodies.map { it.id }.toSet()
        private val names: List<Pair<String, String>> = bodies.filter { it.id.startsWith("person/") }.map { (it.name ?: "") to it.id }
        private val called: Map<String, Set<String>> =
            bodies.associate { b -> b.id to (b.aliases + listOfNotNull(b.name)).toSet() }
        private val decided = mutableMapOf<String, String>()

        fun shipHasBody(id: String): Boolean = id in known

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
        val model: LocalModel?,
        val gate: PatternGate?,
        var modelRuns: Int = 0,
        /** The decision model, when it is on and there is a key for it. */
        val decider: Decider? = null,
        /** The gate's threshold when the gate is on, else null. */
        val threshold: Double? = null,
        var day: DecideDay = DecideDay(),
    )

    /** The decision model, when the owner has turned it on and an OpenRouter key is set. */
    private fun decider(): Pair<Decider, DecideSettings>? {
        val d = decide?.settings?.value?.takeIf { it.on } ?: return null
        val key = cloud?.config?.invoke()?.let(::openRouterKey) ?: return null
        // The bare client: the ship's cookie has no business at OpenRouter.
        return OpenRouterDecider(bare, key, d) to d
    }

    /** Whether the decision model could run here: an OpenRouter key is set. */
    fun decideHasKey(): Boolean = cloud?.config?.invoke()?.let(::openRouterKey) != null

    private suspend fun reading(a: OrreryApi, row: OrreryAccountEntity, s: String): Reading? {
        val view = runCatching { a.state(row.token) }.getOrElse { Log.i(TAG, "state view skipped: ${it.message}"); return null }
        val model = cloudModelIfOn() ?: (if (isLocalTriageSupported) LocalModels.best()?.second else null)
        val emb = embedder
        val gate = if (model != null && emb != null) runCatching {
            PatternGate.build(emb, db.orreryNoticed().snippets(s, "confirmed", GATE_EXAMPLES), db.orreryNoticed().snippets(s, "discarded", GATE_EXAMPLES))
        }.getOrNull() else null
        val dec = if (model != null) decider() else null
        return Reading(
            view.bodies, NameIndex(view.bodies), view.attrs, view.notes, model, gate,
            decider = dec?.first, threshold = dec?.second?.takeIf { it.gate }?.threshold,
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
        s: String,
        nowMs: Long,
        url: String,
        freshMail: List<io.nisfeb.talon.mail.InboxEntry>,
        book: Set<String>,
        remember: (String, String) -> Unit,
    ): Facts {
        val spoken = pendingLock.withLock { transcripts.toList().also { transcripts.clear() } }
        // Status lines change when nothing is said, so they are counted
        // in before the pass decides it has nothing to do.
        val lines = db.contacts().all().filter { it.ship == s || it.ship in book }
            .mapNotNull { c -> contactStatus(c)?.let { (line, at) -> Triple(c.ship, line, at) } }
        val read = db.orrerySent().some(s, lines.map { "status:${it.first}" }).associate { it.key to it.value }
        val fresh = lines.filter { (ship, line, _) -> read["status:$ship"] != line.hashCode().toString(16) }
        if (posts.isEmpty() && spoken.isEmpty() && freshMail.isEmpty() && fresh.isEmpty()) return Facts()
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
            remember(key, "")
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
        // A status line, read the way a message is read. Once per line:
        // the digest is of the words, so a line put back says nothing new.
        for ((ship, line, at) in fresh.take(STATUS_PER_PASS)) {
            remember("status:$ship", line.hashCode().toString(16))
            up += triageText(
                r, s, nowMs, line, ship, at.coerceAtMost(nowMs), "contact:$ship", ship,
                "contacts", "talon://profile/$ship",
            )
        }
        // Mail is addressed to us, so every message in a fresh thread is in scope.
        for (e in freshMail.take(MAIL_THREADS_PER_PASS)) {
            val thread = runCatching { AuspexApi(http, url).thread(e.id) }.getOrNull() ?: continue
            for (msg in thread.messages) {
                if (msg.from == s || msg.body.isBlank()) continue
                up += triageText(r, s, nowMs, msg.body, msg.from, msg.sent.coerceAtMost(nowMs), "mail:${e.id}", msg.id, "mail", "talon://mail/${e.id}")
            }
        }
        tally(s, r.day, nowMs)
        return up
    }

    /**
     * The day's count of what the decision model did, kept with the rest
     * of this install's orrery record and logged as two lines: what the
     * gate read and skipped against what both models cost, and what the
     * status check kept and dropped. Those lines are the case for both.
     */
    private suspend fun tally(s: String, add: DecideDay, nowMs: Long) {
        if (add == DecideDay()) return
        val day = kotlinx.datetime.Instant.fromEpochMilliseconds(nowMs)
            .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault()).date.toString()
        val key = "decide:$day"
        val was = db.orrerySent().get(s, key)?.value
            ?.let { runCatching { Json.decodeFromString(DecideDay.serializer(), it) }.getOrNull() } ?: DecideDay()
        val now = was + add
        db.orrerySent().put(io.nisfeb.talon.data.OrrerySentEntity(s, key, Json.encodeToString(DecideDay.serializer(), now), nowMs))
        now.lines(day).forEach { Log.i(TAG, it) }
    }

    /** What the gate would have done over messages already read, for choosing its threshold. */
    data class GateCheck(
        val lines: List<String>,
        val threshold: Double,
        /** How many of the messages each threshold in the band would have let through. */
        val readAt: List<Pair<Double, Int>>,
        val costUsd: Double,
        val failed: Int,
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
    suspend fun checkGate(limit: Int = 300): Result<GateCheck> = runCatching {
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
            .filter { (m, t) -> t.length >= 8 && !t.trimEnd().endsWith("?") && !t.trimStart().startsWith("/") && inScope(m.whom, t, s, ourNick, allowed) }
            .take(limit).toList().reversed()
        val probs = mutableListOf<Double>()
        val lines = mutableListOf<String>()
        var cost = 0.0
        var failed = 0
        for ((m, text) in picked) {
            val earlier = db.messages().before(m.whom, m.sentMs, ModelExtractor.CONTEXT_MESSAGES).reversed()
                .map { StoryCache.textFor(it.id, it.contentJson) }.filter { it.isNotBlank() }
            val g = Gate.decide(dec, settings.threshold, text, index.authorId(m.author, s), earlier, view.bodies)
            cost += g.costUsd
            val p = g.p
            if (p == null) failed++ else probs += p
            val shown = p?.let { (kotlin.math.round(it * 100) / 100).toString().padEnd(4, '0') } ?: " -- "
            lines += "$shown ${if (g.read) "read" else "skip"} | ${m.author}: ${text.take(90).replace('\n', ' ')}"
        }
        val band = listOf(0.2, 0.25, 0.3, 0.35, 0.4).map { t -> t to (probs.count { it >= t } + failed) }
        GateCheck(lines, settings.threshold, band, cost, failed).also {
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
        // A question states nothing, and the analyst never reads one, so
        // neither does the gate.
        if (r.model != null && worth && r.modelRuns < MODEL_PER_PASS && text.length >= 8 && !text.trimEnd().endsWith("?")) {
            val model = r.model
            val analyst: suspend () -> List<Noticed> = {
                r.modelRuns++
                ModelExtractor.extract(model, r.index, r.bodies, text, author, atMs, s, r.attrs, r.notes, context)
                    .also { r.day = r.day.copy(analystUsd = r.day.analystUsd + (model.lastCostUsd ?: 0.0)) }
            }
            val dec = r.decider
            val from = r.index.authorId(author, s)
            val say: (String) -> Unit = { Log.i(TAG, "$sourceId $it") }
            byModel = if (dec != null && r.threshold != null && !text.trimStart().startsWith("/")) {
                val (g, rows) = Gate.around(dec, r.threshold, text, from, context.map { it.second }, r.bodies, say, analyst)
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
        // This install's key where it has one, else the owner's own say.
        a.transition(db.orreryAccounts().get(s)?.token, id, status, note)
        _actions.value = _actions.value.filterNot { it.id == id }
        refreshActions()
    }

    /**
     * What is waiting for an answer, read now. Needs nothing but orrery
     * on the ship: the pipe feeds orrery, and answering it is another
     * matter.
     */
    suspend fun refreshActions() {
        val a = api ?: return
        val s = ship ?: return
        val url = shipUrl ?: return
        val token = db.orreryAccounts().get(s)?.token
        runCatching { a.actions(token) }
            .onSuccess { _actions.value = it }
            .onFailure { Log.i(TAG, "actions skipped: ${it.message}") }
        // An approved task becomes a todo wherever it can be approved,
        // not only on the install that runs the pipe.
        runCatching { mirrorTasks(a, token, url) }.onFailure { Log.i(TAG, "tasks skipped: ${it.message}") }
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
        private const val BRIEF_LEASE = "orrery-brief"
        const val MESSAGES_PER_PASS = 2000
        const val MAIL_PER_PASS = 200
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
