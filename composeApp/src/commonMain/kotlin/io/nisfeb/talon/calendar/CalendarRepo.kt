package io.nisfeb.talon.calendar

import io.ktor.client.HttpClient
import io.nisfeb.talon.mail.AuspexApi
import io.nisfeb.talon.mail.AuspexError
import io.nisfeb.talon.mail.isSignedOut
import io.nisfeb.talon.data.CalendarCacheEntity
import io.nisfeb.talon.util.Log
import io.nisfeb.talon.util.nowMs
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
import kotlinx.datetime.TimeZone
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
        if (ball.isEmpty()) ball = runCatching { a.config().ball }.getOrDefault("")
        if (!runCatching { a.poke(ball, buildJsonObject { put("action", "config"); put("zone", z) }) }.getOrDefault(false)) return
        delay(400)
        runCatching { a.config() }.getOrNull()?.let { _zone.value = it.zone }
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
        val a = api ?: return
        runCatching { a.window(fromMs, toMs) }
            .onSuccess { w -> _rangeRows.value = w.rows.sortedWith(compareBy({ it.l }, { it.r })) }
            .onFailure { if (it !is AuspexError) throw it; _error.value = it.message }
    }

    private var zoneNames: List<String>? = null
    /** The zone names the editor can offer; read once. */
    suspend fun zones(): List<String> {
        zoneNames?.let { return it }
        val got = api?.let { a -> runCatching { a.zones() }.getOrNull() } ?: return emptyList()
        zoneNames = got
        return got
    }

    /** Make a followed or Google calendar local; false when refused. */
    suspend fun makeLocal(calId: String): Boolean {
        val a = api ?: return false
        val ok = runCatching { a.migrate(calId) }.getOrDefault(false)
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
        if ("google" in kinds) ok = runCatching { a.syncGoogle() }.getOrDefault(false) && ok
        if ("caldav" in kinds) ok = runCatching { a.syncCaldav() }.getOrDefault(false) && ok
        if (_shares.value?.accepted.orEmpty().isNotEmpty()) { lastShareSyncMs = nowMs(); ok = runCatching { a.syncShares() }.getOrDefault(false) && ok }
        delay(1500)
        refresh()
        return ok
    }

    private suspend fun after(settleMs: Long = 0, call: suspend (CalendarApi) -> Boolean): Boolean {
        val a = api ?: return false
        val ok = runCatching { call(a) }.getOrDefault(false)
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
        if (_rows.value != null) return
        val json = AuspexApi.json
        suspend fun read(kind: String) = runCatching { c.read(kind) }.getOrNull().orEmpty()
        val window = read("window").mapNotNull { r ->
            runCatching { json.decodeFromString(CalendarRow.serializer(), r.json) }.getOrNull()
        }
        if (_rows.value == null && window.isNotEmpty()) _rows.value = window
        if (_calendars.value.isEmpty()) {
            _calendars.value = read("calendars").mapNotNull { r ->
                runCatching { json.decodeFromString(CalendarInfo.serializer(), r.json) }.getOrNull()
            }
        }
        if (_tasks.value == null) {
            read("tasks").mapNotNull { r ->
                runCatching { json.decodeFromString(CalendarTask.serializer(), r.json) }.getOrNull()
            }.takeIf { it.isNotEmpty() }?.let { _tasks.value = it }
        }
        if (_tags.value.isEmpty()) _tags.value = read("tags").map { it.json }
        if (_zone.value == null) _zone.value = read("zone").firstOrNull()?.json
    }

    /** Keep what the ship just said, for the next cold start. */
    private suspend fun keep() {
        val c = cache ?: return
        val json = AuspexApi.json
        fun rows(kind: String, texts: List<String>) =
            texts.mapIndexed { i, t -> CalendarCacheEntity(kind, i, t) }
        // All of it or none: the parts are written in turn, and a detach
        // between two of them left a window with no calendars beside it.
        runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            c.replace("window", rows("window", _rows.value.orEmpty().map { json.encodeToString(CalendarRow.serializer(), it) }))
            c.replace("calendars", rows("calendars", _calendars.value.map { json.encodeToString(CalendarInfo.serializer(), it) }))
            c.replace("tasks", rows("tasks", _tasks.value.orEmpty().map { json.encodeToString(CalendarTask.serializer(), it) }))
            c.replace("tags", rows("tags", _tags.value))
            c.replace("zone", rows("zone", listOfNotNull(_zone.value)))
            }
        }.onFailure { Log.w(TAG, "calendar not kept", it) }
    }

    suspend fun windowRows(fromMs: Long, toMs: Long): List<CalendarRow>? =
        api?.let { a -> runCatching { a.window(fromMs, toMs).rows.sortedWith(compareBy({ it.l }, { it.r })) }.getOrNull() }

    /** The calendars an event can be added to: all but those shared with us read-only. */
    fun writable(): List<CalendarInfo> = _calendars.value.filter { it.id !in readOnly }
    private fun writableDefault(): String? =
        defaultCalendar.value.takeIf { d -> writable().any { it.id == d } } ?: writable().firstOrNull()?.id

    /** An event someone shared, as moments, onto [calId] (else the calendar new events go to). False when refused. */
    suspend fun addShared(calId: String?, title: String, startMs: Long, endMs: Long): Boolean {
        val zoneId = _zone.value ?: TimeZone.currentSystemDefault().id
        val zone = runCatching { TimeZone.of(zoneId) }.getOrElse { TimeZone.currentSystemDefault() }
        return poke(eventBody(sharedDraft(title, startMs, endMs, calId ?: writableDefault(), zone, zoneId)))
    }

    /** Every event in an .ics onto [calId] (else the calendar new events go to). False when refused. */
    suspend fun importIcs(calId: String?, ics: String): Boolean {
        val a = api ?: return false
        val ok = runCatching { a.importIcs(calId ?: writableDefault() ?: "default", ics) }.getOrDefault(false)
        if (ok) refreshAll()
        return ok
    }

    /** Tick or untick a task. */
    suspend fun setDone(id: String, done: Boolean): Boolean = poke(doneBody(id, done))

    suspend fun eventDetail(id: String): JsonObject? =
        api?.let { a -> runCatching { a.event(id) }.getOrNull() }

    /** A write, then the reads that show it. False when refused. */
    suspend fun poke(body: JsonObject): Boolean {
        val a = api ?: return false
        if (ball.isEmpty()) ball = runCatching { a.config().ball }.getOrDefault("")
        val ok = runCatching { a.poke(ball, body) }.getOrDefault(false)
        if (ok) {
            // The nexus applies a poke after it answers; give it a beat.
            delay(400)
            refresh()
            range?.let { (f, t) -> loadRange(f, t) }
        }
        return ok
    }

    fun attach(baseUrl: String) {
        if (api != null && shipUrl == baseUrl) return
        shipUrl = baseUrl
        zoneAdopted = false
        zoneNames = null
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
                api?.let { a -> runCatching { a.syncShares() } }
                delay(1500)
            }
            refresh()
        }
    }

    suspend fun refresh() = gate.withLock {
        val a = api ?: return@withLock
        try {
            val now = nowMs()
            val w = a.window(now - BEHIND_MS, now + AHEAD_MS)
            _rows.value = w.rows.sortedWith(compareBy({ it.l }, { it.r }))
            _calendars.value = runCatching { a.calendars() }.getOrNull() ?: _calendars.value
            _tasks.value = runCatching { a.tasks() }.getOrNull() ?: _tasks.value
            // Null only when the calendar has no sharing (404); a hiccup
            // keeps the last answer, and with it the read-only guard.
            _shares.value = runCatching { a.shares() }.getOrElse { e ->
                if (e is AuspexError.Refused && e.status == AuspexApi.NOT_FOUND) null else _shares.value
            }
            _conflicts.value = runCatching { a.conflicts() }.getOrElse { _conflicts.value }
            _sync.value = buildMap {
                runCatching { a.google() }.getOrNull()?.linked?.forEach { (id, row) -> put(id, row) }
                runCatching { a.caldavSubscriptions() }.getOrNull()?.forEach { put(it.id, SyncRow(it.lastMs, it.error)) }
                _shares.value?.accepted?.forEach { (id, acc) -> put(id, SyncRow(acc.lastMs, acc.error)) }
            }.ifEmpty { if (_calendars.value.any { it.kind != "local" }) _sync.value else emptyMap() }
            _tags.value = runCatching { a.tags() }.getOrNull()?.map { it.tag } ?: _tags.value
            keep()
            runCatching { a.config() }.getOrNull()?.let { _zone.value = it.zone; ball = it.ball }
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
    }
}
