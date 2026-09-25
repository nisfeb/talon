package io.nisfeb.talon.calendar

import io.ktor.client.HttpClient
import io.nisfeb.talon.mail.AuspexApi
import io.nisfeb.talon.mail.AuspexError
import io.nisfeb.talon.mail.isSignedOut
import io.nisfeb.talon.data.CalendarCacheEntity
import io.nisfeb.talon.util.Log
import io.nisfeb.talon.util.nowMs
import io.nisfeb.talon.util.runSuspendCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class CalendarAvailability { UNKNOWN, PRESENT, ABSENT, SIGNED_OUT }

/**
 * The next few weeks of the ship's calendar, read on a timer.
 *
 * The calendar owns every fact; this holds the last window and asks
 * again every ten minutes, on coming back to the app, and on request.
 * The window runs from a few hours ago -- an occurrence under way
 * still counts -- to thirty days out, which is as far as "next" ever
 * has to look.
 */
class CalendarRepo(
    private val http: HttpClient,
    private val scope: CoroutineScope,
    private val pollIntervalMs: Long = 10 * 60 * 1000L,
    /** This ship's last answer, kept across restarts. Null keeps none. */
    private val cache: io.nisfeb.talon.data.CalendarCacheDao? = null,
) {
    private var api: CalendarApi? = null
    private var poller: Job? = null
    private var foreground = true
    private val gate = Mutex()

    private val _availability = MutableStateFlow(CalendarAvailability.UNKNOWN)
    val availability: StateFlow<CalendarAvailability> = _availability.asStateFlow()
    private val _rows = MutableStateFlow<List<CalendarRow>?>(null)
    /** Occurrences in the window, in time order; null before the first answer. */
    val rows: StateFlow<List<CalendarRow>?> = _rows.asStateFlow()
    private val _calendars = MutableStateFlow<List<CalendarInfo>>(emptyList())
    val calendars: StateFlow<List<CalendarInfo>> = _calendars.asStateFlow()
    private val _tasks = MutableStateFlow<List<CalendarTask>?>(null)
    /** Every task, open or done, dated or not; null before the first answer. */
    val tasks: StateFlow<List<CalendarTask>?> = _tasks.asStateFlow()
    private val _pendingTasks = MutableStateFlow<List<CalendarTask>>(emptyList())
    /** Tasks written and not yet read back, shown in flight until the calendar's own copy arrives. */
    val pendingTasks: StateFlow<List<CalendarTask>> = _pendingTasks.asStateFlow()
    private var ghosts = 0
    /** Every synced calendar's last pull and error, by id: Google, followed, and shared with us. */
    private val _sync = MutableStateFlow<Map<String, SyncRow>>(emptyMap())
    val sync: StateFlow<Map<String, SyncRow>> = _sync.asStateFlow()
    /** Pushes the remote refused and changes made on both sides, as the calendar logged them. */
    private val _conflicts = MutableStateFlow<List<SyncConflict>>(emptyList())
    val conflicts: StateFlow<List<SyncConflict>> = _conflicts.asStateFlow()
    suspend fun clearConflicts(): Boolean = after { it.clearConflicts() }
    /** The calendar a new event goes to unless another is picked; "" for the first one. Seeded by the shell. */
    val defaultCalendar = MutableStateFlow("")
    /** The top half shows the week rather than the month. Per device, seeded by the shell. */
    val weekView = MutableStateFlow(false)
    private val _shares = MutableStateFlow<Shares?>(null)
    /** Sharing with ships; null on a calendar too old to have it. */
    val shares: StateFlow<Shares?> = _shares.asStateFlow()
    /** Calendars shared with us read-only: the host silently drops
     *  every edit poke to them, so the client must not offer one. */
    val readOnly: Set<String> get() = _shares.value?.readOnly.orEmpty()
    private val _tags = MutableStateFlow<List<String>>(emptyList())
    /** Every tag in use on the ship's calendar, for a filter. */
    val tags: StateFlow<List<String>> = _tags.asStateFlow()
    private val _zone = MutableStateFlow<String?>(null)
    /** The calendar's display zone, or null for the device's. */
    val zone: StateFlow<String?> = _zone.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null)
    /** Something done on the reader's behalf, said once. */
    val notice: StateFlow<String?> = _notice.asStateFlow()
    fun clearNotice() { _notice.value = null }
    private var zoneAdopted = false

    /** The device's zone, in the calendar's words, or null if unknown to it. */
    suspend fun deviceZone(): String? {
        val id = TimeZone.currentSystemDefault().id
        return id.takeIf { it in zones() }
    }

    /** Set the calendar's own zone: the one its times are read in. */
    suspend fun setZone(zone: String): Boolean = poke(buildJsonObject { put("action", "config"); put("zone", zone) })

    /**
     * A calendar with no zone reads every wall clock as UTC, so an
     * event made for 4pm shows at 4pm UTC on every clock but the
     * editor's. Done once per attach: the device's zone becomes the
     * calendar's, and the reader is told.
     */
    private suspend fun adoptZoneIfNone() {
        if (zoneAdopted || _zone.value != null) return
        zoneAdopted = true
        val z = deviceZone() ?: return
        val a = api ?: return
        if (ball.isEmpty()) ball = runSuspendCatching { a.config().ball }.getOrDefault("")
        if (!runSuspendCatching { a.poke(ball, buildJsonObject { put("action", "config"); put("zone", z) }) }.getOrDefault(false)) return
        delay(400)
        runSuspendCatching { a.config() }.getOrNull()?.let { _zone.value = it.zone }
        if (_zone.value == z) {
            _notice.value = "The calendar had no zone, so its times were read as UTC. It is now $z. An event made before this keeps its old time until it is saved again."
            Log.i(TAG, "calendar zone was unset; adopted $z")
        }
    }
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    /** What config.json named as the poke target; empty until read. */
    private var ball: String = ""
    /** Calendars the reader has switched off on this device. */
    private val _hidden = MutableStateFlow<Set<String>>(emptySet())
    val hidden: StateFlow<Set<String>> = _hidden.asStateFlow()
    fun setHidden(id: String, off: Boolean) {
        _hidden.value = if (off) _hidden.value + id else _hidden.value - id
    }
    /** What this device had switched off last time. */
    fun seedHidden(ids: Set<String>) { _hidden.value = ids }

    /** Everything again: the widget's window, the screen's month, and the rest. */
    suspend fun refreshAll() {
        refresh()
        range?.let { (f, t) -> loadRange(f, t) }
    }
    /** The calendar screen's own window (a month at a time), apart
     *  from the widget's; null before the first answer. */
    private val _rangeRows = MutableStateFlow<List<CalendarRow>?>(null)
    val rangeRows: StateFlow<List<CalendarRow>?> = _rangeRows.asStateFlow()
    private var range: Pair<Long, Long>? = null

    suspend fun loadRange(fromMs: Long, toMs: Long) {
        range = fromMs to toMs
        // The month is on screen from the last answer while this one is
        // asked for: the screen reads these rows, not the window's.
        if (_rangeRows.value == null) restore()
        val a = api ?: return
        runSuspendCatching { a.window(fromMs, toMs) }
            .onSuccess { w ->
                _rangeRows.value = w.rows.sortedWith(compareBy({ it.l }, { it.r }))
                // Kept as it lands, not at the next refresh: a phone closed
                // on a month it has just read opens on that month again.
                // Only the month: nothing else changed by turning a page.
                keepRange()
            }
            .onFailure { if (it !is AuspexError) throw it; _error.value = it.message }
    }

    private var zoneNames: List<String>? = null
    /** The zone names the editor can offer; read once. */
    suspend fun zones(): List<String> {
        zoneNames?.let { return it }
        val got = api?.let { a -> runSuspendCatching { a.zones() }.getOrNull() } ?: return emptyList()
        zoneNames = got
        return got
    }

    /** Make a followed or Google calendar local; false when refused. */
    suspend fun makeLocal(calId: String): Boolean {
        val a = api ?: return false
        val ok = runSuspendCatching { a.migrate(calId) }.getOrDefault(false)
        if (ok) refresh()
        return ok
    }

    /** Share a local calendar with a ship. Null: refused. False: recorded,
     *  but the ship could not be told (down, or no calendar yet). A ship
     *  that cannot be reached at all is an error, not a refusal. */
    suspend fun share(calId: String, ship: String, edit: Boolean): Boolean? {
        val a = api ?: return null
        val told = try {
            a.share(calId, ship, edit)
        } catch (e: AuspexError.Unreachable) {
            _error.value = e.message
            throw e
        }
        refresh()
        return told
    }
    suspend fun revoke(calId: String, ship: String): Boolean = after { it.revoke(calId, ship) }
    suspend fun decline(key: String): Boolean = after { it.decline(key) }
    suspend fun accept(key: String): Boolean = after(settleMs = 1500) { it.accept(key) }
    suspend fun syncShares(): Boolean { lastShareSyncMs = nowMs(); return after(settleMs = 1500) { it.syncShares() } }

    /** Every sync this calendar has, prodded now: Google, followed, shared. */
    suspend fun syncNow(): Boolean {
        val a = api ?: return false
        val kinds = _calendars.value.map { it.kind }.toSet()
        var ok = true
        if ("google" in kinds) ok = runSuspendCatching { a.syncGoogle() }.getOrDefault(false) && ok
        if ("caldav" in kinds) ok = runSuspendCatching { a.syncCaldav() }.getOrDefault(false) && ok
        if (_shares.value?.accepted.orEmpty().isNotEmpty()) { lastShareSyncMs = nowMs(); ok = runSuspendCatching { a.syncShares() }.getOrDefault(false) && ok }
        delay(1500)
        refresh()
        return ok
    }

    private suspend fun after(settleMs: Long = 0, call: suspend (CalendarApi) -> Boolean): Boolean {
        val a = api ?: return false
        val ok = runSuspendCatching { call(a) }.getOrDefault(false)
        if (ok) { if (settleMs > 0) delay(settleMs); refresh() }
        return ok
    }

    /** Occurrences between two moments, read without moving the screen's month or the widget's window. */
    /**
     * Put the last answer back on screen, unless the ship has already
     * answered. Anything unreadable is dropped rather than shown: a row
     * written by an older build is not worth a crash.
     */
    private suspend fun restore() {
        val c = cache ?: return
        if (_rows.value != null && _rangeRows.value != null) return
        val json = AuspexApi.json
        suspend fun read(kind: String) = runSuspendCatching { c.read(kind) }.getOrNull().orEmpty()
        fun rows(kind: List<io.nisfeb.talon.data.CalendarCacheEntity>) = kind.mapNotNull { r ->
            runSuspendCatching { json.decodeFromString(CalendarRow.serializer(), r.json) }.getOrNull()
        }
        // Read it all, then put it up in one go: a screen that sees the
        // month must see the calendars it colours them by, and anything
        // read between two assignments let it see one without the other.
        val window = rows(read("window"))
        val month = rows(read("range")).ifEmpty { window }
        val calendars = read("calendars").mapNotNull { r ->
            runSuspendCatching { json.decodeFromString(CalendarInfo.serializer(), r.json) }.getOrNull()
        }
        val tasks = read("tasks").mapNotNull { r ->
            runSuspendCatching { json.decodeFromString(CalendarTask.serializer(), r.json) }.getOrNull()
        }
        val tags = read("tags").map { it.json }
        val zone = read("zone").firstOrNull()?.json
        if (_rows.value == null && window.isNotEmpty()) _rows.value = window
        // The screen's own month, kept beside the window: without it the
        // grid was empty on every cold start until the ship answered,
        // though the last answer was in the database all along.
        if (_rangeRows.value == null && month.isNotEmpty()) _rangeRows.value = month
        if (_calendars.value.isEmpty() && calendars.isNotEmpty()) _calendars.value = calendars
        if (_tasks.value == null && tasks.isNotEmpty()) _tasks.value = tasks
        if (_tags.value.isEmpty() && tags.isNotEmpty()) _tags.value = tags
        if (_zone.value == null && zone != null) _zone.value = zone
    }

    /**
     * Keep what the ship just said, for the next cold start. One at a
     * time, and the snapshot taken inside the lock: a refresh that
     * started before a month was read would otherwise write the state
     * as it was when it began, over the month just kept.
     */
    private val keepLock = Mutex()

    private suspend fun keep() {
        keepLock.withLock {
        val c = cache ?: return
        val json = AuspexApi.json
        fun rows(kind: String, texts: List<String>) =
            texts.mapIndexed { i, t -> CalendarCacheEntity(kind, i, t) }
        // Snapshot first: the flows empty on a ship switch, and reading
        // them lazily between writes stored a fresh window beside
        // empty calendars. Then one transaction, all of it or none.
        val snapWindow = _rows.value
        // Null here means a detach emptied the flows between the fetch and
        // this save. Writing that would replace a good cache with an
        // empty one; the next attach reads again anyway.
        if (snapWindow == null) return
        val snapCalendars = _calendars.value
        val snapTasks = _tasks.value
        val snapTags = _tags.value
        val snapZone = _zone.value
        val snapRange = _rangeRows.value
        runSuspendCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            c.replaceAll(
                mapOf(
                    "window" to rows("window", snapWindow.orEmpty().map { json.encodeToString(CalendarRow.serializer(), it) }),
                    "calendars" to rows("calendars", snapCalendars.map { json.encodeToString(CalendarInfo.serializer(), it) }),
                    "tasks" to rows("tasks", snapTasks.orEmpty().map { json.encodeToString(CalendarTask.serializer(), it) }),
                    "tags" to rows("tags", snapTags),
                    "zone" to rows("zone", listOfNotNull(snapZone)),
                    "range" to rows("range", snapRange.orEmpty().map { json.encodeToString(CalendarRow.serializer(), it) }),
                ),
            )
            }
        }.onFailure { Log.w(TAG, "calendar not kept", it) }
        }
    }

    /** The month on screen alone, kept as it lands: the rest has not moved. */
    private suspend fun keepRange() {
        keepLock.withLock {
            val c = cache ?: return
            val snap = _rangeRows.value ?: return
            runSuspendCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    c.replace("range", snap.mapIndexed { i, r -> CalendarCacheEntity("range", i, AuspexApi.json.encodeToString(CalendarRow.serializer(), r)) })
                }
            }.onFailure { Log.w(TAG, "calendar range not kept", it) }
        }
    }

    suspend fun windowRows(fromMs: Long, toMs: Long): List<CalendarRow>? =
        api?.let { a -> runSuspendCatching { a.window(fromMs, toMs).rows.sortedWith(compareBy({ it.l }, { it.r })) }.getOrNull() }

    /** The calendars an event can be added to: all but those shared with us read-only. */
    fun writable(): List<CalendarInfo> = _calendars.value.filter { it.id !in readOnly }
    private fun writableDefault(): String? =
        defaultCalendar.value.takeIf { d -> writable().any { it.id == d } } ?: writable().firstOrNull()?.id

    /** An event someone shared, as moments, onto [calId] (else the calendar new events go to). False when refused. */
    suspend fun addShared(calId: String?, title: String, startMs: Long, endMs: Long): Boolean {
        val zoneId = _zone.value ?: TimeZone.currentSystemDefault().id
        val zone = runSuspendCatching { TimeZone.of(zoneId) }.getOrElse { TimeZone.currentSystemDefault() }
        return pokeEvent(eventBody(sharedDraft(title, startMs, endMs, calId ?: writableDefault(), zone, zoneId)))
    }

    /** Every event in an .ics onto [calId] (else the calendar new events go to). False when refused. */
    suspend fun importIcs(calId: String?, ics: String): Boolean {
        val a = api ?: return false
        val ok = runSuspendCatching { a.importIcs(calId ?: writableDefault() ?: "default", ics) }.getOrDefault(false)
        if (ok) refreshAll()
        return ok
    }

    /** Tick or untick a task. */
    suspend fun setDone(id: String, done: Boolean): Boolean = write(doneBody(id, done)).also { if (it) afterItemWrite() }

    /**
     * A new task, on the list at once and written behind it. The write
     * runs here, not in a screen, so leaving the screen does not lose it;
     * the stand-in stays until the refresh after the write brings the
     * calendar's own copy. A refusal takes the stand-in away and says so
     * through [onFailed]. Not retried: a write whose answer was lost may
     * have landed, and a retry would make it twice.
     */
    fun addTask(d: EventDraft, onFailed: (String) -> Unit = {}): CalendarTask {
        val ghost = CalendarTask(
            id = "pending-${nowMs()}-${ghosts++}",
            cal = d.cal ?: writableDefault() ?: "default",
            cat = "todo",
            meta = buildJsonObject {
                put("name", d.name.trim())
                if (d.note.isNotBlank()) put("note", d.note.trim())
                if (d.tags.isNotEmpty()) put("tags", kotlinx.serialization.json.JsonArray(d.tags.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            },
            // The calendar's own due for a day: midnight UTC.
            dueMs = d.due?.let { kotlinx.datetime.LocalDateTime(it.year, it.monthNumber, it.dayOfMonth, 0, 0).toInstant(TimeZone.UTC).toEpochMilliseconds() },
        )
        _pendingTasks.value = _pendingTasks.value + ghost
        scope.launch {
            val ok = write(eventBody(d))
            if (ok) {
                afterItemWrite()
                // A tag the calendar has not seen before joins the list
                // the editor offers; one it has is already there.
                if (d.tags.any { it !in _tags.value }) api?.let { a -> runSuspendCatching { a.tags() }.getOrNull()?.let { _tags.value = it.map { t -> t.tag } } }
            }
            // Why first, then the stand-in goes: the other way round, a
            // screen watching the list saw it leave before it heard why.
            if (!ok) onFailed("The ship did not take \"${d.name.trim()}\".")
            _pendingTasks.value = _pendingTasks.value - ghost
        }
        return ghost
    }

    /** Any other write, carried on here whatever the screen does. */
    fun writeInBackground(body: JsonObject, onFailed: () -> Unit = {}) {
        scope.launch { if (!pokeEvent(body)) onFailed() }
    }

    /**
     * One event's full rule breakdown, as the editor needs it.
     *
     * Kept once read. The editor cannot open without it, and every
     * request into a grubbery app is about a second of the ship's
     * single thread and they queue, so tapping Edit sat on a blank
     * screen for as long as the queue was. A write clears this, since
     * the ship's answer is then the one that counts.
     */
    suspend fun eventDetail(id: String): JsonObject? =
        details[id] ?: (reading[id] ?: readDetail(id)).await()

    /** One read of [id] at a time: Edit tapped while the viewer's read is out waits for that one, not a second behind it. */
    private fun readDetail(id: String): kotlinx.coroutines.Deferred<JsonObject?> = reading.getOrPut(id) {
        // Started once it is in the map, so the removal at its end cannot come first.
        scope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                api?.let { a -> runSuspendCatching { a.event(id) }.getOrNull() }?.also { details[id] = it }
            } finally {
                reading.remove(id)
            }
        }
    }.also { it.start() }
    private val reading = io.nisfeb.talon.util.ConcurrentMap<String, kotlinx.coroutines.Deferred<JsonObject?>>()

    /**
     * Read one ahead of being asked for it: the viewer is the step
     * before Edit, so the round trip happens while the owner is reading
     * rather than while they are waiting for a screen.
     */
    fun prefetchEvent(id: String) {
        if (api == null) return
        // Read again at every view, the step before Edit. A copy kept
        // from earlier opened the editor on the event as it was then,
        // and saving wrote that back over whatever another device, or
        // orrery's executor, had changed since. Taken out first, so an
        // Edit tapped during the read waits for the new copy.
        details.remove(id)
        readDetail(id)
    }

    private val details = io.nisfeb.talon.util.ConcurrentMap<String, JsonObject>()

    /**
     * An event or task written, then only what it can change read back
     * ([afterItemWrite]). A save went through [poke]'s nine reads, one
     * behind another on the ship's single thread, and on a busy ship the
     * edit sat greyed out for minutes. False when refused.
     */
    suspend fun pokeEvent(body: JsonObject): Boolean = write(body).also { if (it) afterItemWrite() }

    /**
     * A write, then every read a refresh makes: for what changes the
     * calendars themselves, their names, sharing or zone. An event or a
     * task goes through [pokeEvent]. False when refused.
     */
    suspend fun poke(body: JsonObject): Boolean = write(body).also { ok ->
        if (ok) {
            refresh()
            range?.let { (f, t) -> loadRange(f, t) }
        }
    }

    /** A write and nothing read back: the caller knows what it changed. False when refused. */
    private suspend fun write(body: JsonObject): Boolean {
        val a = api ?: return false
        if (ball.isEmpty()) ball = runSuspendCatching { a.config().ball }.getOrDefault("")
        val ok = runSuspendCatching { a.poke(ball, body) }.getOrDefault(false)
        if (ok) {
            // What was read of an event the write may have changed is
            // no longer what the ship says.
            details.clear()
            // The nexus applies a poke after it answers; give it a beat.
            delay(400)
        }
        return ok
    }

    /**
     * What an event or task write can have changed, read back: the task
     * listing, the window (the home screen's agenda reads it), and the
     * month on screen. Not the nine reads a full refresh makes: every
     * request into a grubbery app is about a second of its single
     * thread, one behind another, and a tick went through all nine.
     */
    private suspend fun afterItemWrite() {
        // The ship answers a write before it applies it, and a busy one
        // applies it seconds later: read once, the old copy came back,
        // the edit's stand-in went, and the change was not shown until
        // the next poll, ten minutes on. So read until something moved,
        // a few times at most, waiting longer each time.
        val before = Triple(_tasks.value, _rows.value, _rangeRows.value)
        var pause = 500L
        for (attempt in 1..AFTER_WRITE_READS) {
            refreshTasks()
            refreshWindow()
            range?.let { (f, t) -> loadRange(f, t) }
            if (Triple(_tasks.value, _rows.value, _rangeRows.value) != before || attempt == AFTER_WRITE_READS) break
            delay(pause)
            pause *= 2
        }
        // So a phone closed straight after a tick opens on the tick.
        keep()
    }

    /** The window alone, read again. */
    private suspend fun refreshWindow() {
        val a = api ?: return
        val now = nowMs()
        runSuspendCatching { a.window(now - BEHIND_MS, now + AHEAD_MS) }
            .onSuccess { w -> _rows.value = w.rows.sortedWith(compareBy({ it.l }, { it.r })) }
    }

    fun attach(baseUrl: String) {
        if (api != null && shipUrl == baseUrl) return
        shipUrl = baseUrl
        zoneAdopted = false
        zoneNames = null
        details.clear()
        api = CalendarApi(http, baseUrl)
        clearShipState()
        poller?.cancel()
        poller = scope.launch {
            // What the ship last said, while it is asked again. A month
            // that was right ten minutes ago beats an empty grid.
            restore()
            refresh()
            while (isActive) {
                delay(pollIntervalMs)
                if (foreground && _availability.value != CalendarAvailability.SIGNED_OUT) refresh()
            }
        }
    }
    private var shipUrl: String? = null

    /** Nothing of the last ship may greet the next one: a stale zone
     *  would stop adoptZoneIfNone, and stale rows would pose as the
     *  new ship's own until it answered. */
    private fun clearShipState() {
        _availability.value = CalendarAvailability.UNKNOWN
        _rows.value = null
        _rangeRows.value = null
        range = null
        _tasks.value = null
        _calendars.value = emptyList()
        _shares.value = null
        _tags.value = emptyList()
        _zone.value = null
        _conflicts.value = emptyList()
        _sync.value = emptyMap()
        _error.value = null
        ball = ""
        lastShareSyncMs = 0
    }

    fun detach() {
        poller?.cancel()
        poller = null
        api = null
        shipUrl = null
        zoneAdopted = false
        zoneNames = null
        details.clear()
        clearShipState()
    }

    private var lastShareSyncMs = 0L

    fun setForeground(on: Boolean) {
        val was = foreground
        foreground = on
        if (on && !was) scope.launch {
            // Coming back: a shared calendar is pulled now rather than on
            // the next pass, but not more than every few minutes; the
            // route prods the whole sync fiber. The refresh runs either way.
            val now = nowMs()
            if (_shares.value?.accepted.orEmpty().isNotEmpty() && now - lastShareSyncMs > SHARE_SYNC_GAP_MS) {
                lastShareSyncMs = now
                api?.let { a -> runSuspendCatching { a.syncShares() } }
                delay(1500)
            }
            refresh()
        }
    }

    /**
     * The tasks, and nothing else.
     *
     * A full refresh asks the ship nine times: the window, the
     * calendars, the whole listing, the shares, the conflicts, Google,
     * the CalDAV subscriptions, the tags and the config. Every request
     * into a grubbery app is about a second of its single thread and
     * they queue, so refreshing a task list cost the lot of them.
     * Ticking a box and pulling the list are one request now.
     */
    suspend fun refreshTasks(): Result<Unit> = gate.withLock {
        val a = api ?: return@withLock Result.failure(IllegalStateException("Not attached to a ship."))
        runSuspendCatching {
            _tasks.value = a.tasks()
            _availability.value = CalendarAvailability.PRESENT
            _error.value = null
        }.onFailure { e ->
            if (e is AuspexError && e.isSignedOut) _availability.value = CalendarAvailability.SIGNED_OUT
            _error.value = e.message
        }
    }

    suspend fun refresh() = gate.withLock {
        val a = api ?: return@withLock
        try {
            val now = nowMs()
            val w = a.window(now - BEHIND_MS, now + AHEAD_MS)
            _rows.value = w.rows.sortedWith(compareBy({ it.l }, { it.r }))
            // The ship has just said what is current, so nothing read of
            // one event before now is: the assistant reads through here too.
            details.clear()
            _calendars.value = runSuspendCatching { a.calendars() }.getOrNull() ?: _calendars.value
            _tasks.value = runSuspendCatching { a.tasks() }.getOrNull() ?: _tasks.value
            // Null only when the calendar has no sharing (404); a hiccup
            // keeps the last answer, and with it the read-only guard.
            _shares.value = runSuspendCatching { a.shares() }.getOrElse { e ->
                if (e is AuspexError.Refused && e.status == AuspexApi.NOT_FOUND) null else _shares.value
            }
            _conflicts.value = runSuspendCatching { a.conflicts() }.getOrElse { _conflicts.value }
            _sync.value = buildMap {
                runSuspendCatching { a.google() }.getOrNull()?.linked?.forEach { (id, row) -> put(id, row) }
                runSuspendCatching { a.caldavSubscriptions() }.getOrNull()?.forEach { put(it.id, SyncRow(it.lastMs, it.error)) }
                _shares.value?.accepted?.forEach { (id, acc) -> put(id, SyncRow(acc.lastMs, acc.error)) }
            }.ifEmpty { if (_calendars.value.any { it.kind != "local" }) _sync.value else emptyMap() }
            _tags.value = runSuspendCatching { a.tags() }.getOrNull()?.map { it.tag } ?: _tags.value
            keep()
            runSuspendCatching { a.config() }.getOrNull()?.let { _zone.value = it.zone; ball = it.ball }
            _availability.value = CalendarAvailability.PRESENT
            _error.value = null
            adoptZoneIfNone()
        } catch (e: AuspexError) {
            when {
                e.isSignedOut -> {
                    _availability.value = CalendarAvailability.SIGNED_OUT
                    poller?.cancel(); poller = null
                }
                e is AuspexError.Refused && e.status == AuspexApi.NOT_FOUND ->
                    _availability.value = CalendarAvailability.ABSENT
                else -> {
                    _error.value = e.message
                    Log.w(TAG, "calendar refresh failed", e)
                }
            }
        }
    }

    companion object {
        private const val TAG = "CalendarRepo"
        const val BEHIND_MS = 6 * 60 * 60 * 1000L
        const val AHEAD_MS = 30L * 24 * 60 * 60 * 1000L
        const val SHARE_SYNC_GAP_MS = 5 * 60 * 1000L
        /** Read-backs after a write before taking the ship at its word: about 30s of waiting. */
        const val AFTER_WRITE_READS = 6
    }
}
