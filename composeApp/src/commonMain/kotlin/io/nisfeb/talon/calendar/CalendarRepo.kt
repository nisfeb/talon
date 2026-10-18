package io.nisfeb.talon.calendar

import kotlinx.datetime.number
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
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

    private val _leadMin = MutableStateFlow<Int?>(null)
    /** Minutes of the heads-up the ship sends before every timed event, 0 for none; null from a calendar too old to say. */
    val leadMin: StateFlow<Int?> = _leadMin.asStateFlow()
    private val _remindersKnown = MutableStateFlow(false)
    /**
     * Whether the calendar reads and takes reminders: its config or its
     * rows say so. An older one says neither, and then the editor shows
     * none, since a reminder set there would go nowhere.
     */
    val remindersKnown: StateFlow<Boolean> = _remindersKnown.asStateFlow()
    private fun noteReminders(rows: List<CalendarRow>) {
        if (!_remindersKnown.value && (_leadMin.value != null || rows.any { it.alarms != null })) _remindersKnown.value = true
    }

    /** Set the heads-up before every timed event, in minutes; 0 turns it off. */
    suspend fun setLeadMin(min: Int): Boolean =
        poke(buildJsonObject { put("action", "config"); put("lead_min", min) }).also { if (it) _leadMin.value = min }

    /**
     * A calendar with no zone reads every wall clock as UTC, so an
     * event made for 4pm shows at 4pm UTC on every clock but the
     * editor's. Done once per attach: the device's zone becomes the
     * calendar's, and the reader is told. Only after a config read that
     * said no zone ([refresh]): after one that failed, the device's zone
     * went over the calendar's own, a traveller's phone moving it.
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
                noteReminders(w.rows)
                // Kept as it lands, not at the next refresh: a phone closed
                // on a month it has just read opens on that month again.
                // Only the month: nothing else changed by turning a page.
                keepRange()
            }
            .onFailure { if (it !is AuspexError) throw it; _error.value = it.said() }
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
    suspend fun makeLocal(calId: String): Boolean = carry {
        val a = api ?: return@carry false
        val ok = tried { a.migrate(calId) }
        if (ok) refresh()
        ok
    }

    /** Share a local calendar with a ship. Null: refused. False: recorded,
     *  but the ship could not be told (down, or no calendar yet). A ship
     *  that cannot be reached at all is an error, not a refusal. */
    suspend fun share(calId: String, ship: String, edit: Boolean): Boolean? = carry {
        val a = api ?: return@carry null
        val told = try {
            a.share(calId, ship, edit)
        } catch (e: AuspexError.Unreachable) {
            _error.value = e.said()
            throw e
        }
        refresh()
        told
    }
    suspend fun revoke(calId: String, ship: String): Boolean = after { it.revoke(calId, ship) }
    suspend fun decline(key: String): Boolean = after { it.decline(key) }
    suspend fun accept(key: String): Boolean = after(settleMs = 1500) { it.accept(key) }
    suspend fun syncShares(): Boolean { lastShareSyncMs = nowMs(); return after(settleMs = 1500) { it.syncShares() } }

    private val _invites = MutableStateFlow<List<CalendarInvite>?>(null)
    /** Invitations to single events from other ships; null until read, and on a calendar too old to have them. */
    val invites: StateFlow<List<CalendarInvite>?> = _invites.asStateFlow()
    private val _meetings = MutableStateFlow<Boolean?>(null)
    /**
     * Whether the calendar has meetings (version 34): invitations, guests,
     * video calls, and "only this one" as one step. Null until asked;
     * invites.json's 404 says no, as shares.json's does for sharing.
     */
    val meetings: StateFlow<Boolean?> = _meetings.asStateFlow()
    @kotlin.concurrent.Volatile private var invitesReadMs = 0L

    /**
     * Read the invitations, and with them whether the calendar has
     * meetings. Not part of a refresh: the screen asks once its month is
     * in, so the read never queues on the ship in front of the month. A
     * hiccup keeps the last answer.
     */
    suspend fun refreshInvites() {
        val a = api ?: return
        val got = runSuspendCatching { a.invites() }
        if (api !== a) return
        got.onSuccess { _invites.value = it; _meetings.value = true; invitesReadMs = nowMs() }
            .onFailure { e ->
                if (e is AuspexError.Refused && e.status == AuspexApi.NOT_FOUND) {
                    _invites.value = null; _meetings.value = false; invitesReadMs = nowMs()
                }
            }
    }

    /**
     * [refreshInvites], unless they were read in the last minute. A
     * calendar without them is asked hourly, as a full read is: it may
     * have been upgraded since.
     */
    suspend fun refreshInvitesIfStale() {
        val fresh = if (_meetings.value == false) FULL_READ_EVERY_MS else OPEN_FRESH_MS
        if (nowMs() - invitesReadMs >= fresh) refreshInvites()
    }

    @kotlin.concurrent.Volatile private var saidRefusalMs = 0L

    /**
     * Why the calendar refused a write it had taken, as its own page
     * asks: it answers a poke before reading it, and keeps what it
     * refused, with why, in outcomes.json. After [REFUSAL_WAIT_MS], the
     * newest refusal of [id] (the poke's, "" for an add) since [sinceMs],
     * less a minute's slack for the ship's clock, each said once. Null for
     * none, or for a calendar that keeps no record.
     */
    suspend fun refusal(id: String, sinceMs: Long): String? = carry {
        delay(REFUSAL_WAIT_MS)
        val a = api ?: return@carry null
        val got = runSuspendCatching { a.outcomes() }.getOrNull() ?: return@carry null
        val r = got.refusals.lastOrNull { it.atMs > saidRefusalMs && it.atMs >= sinceMs - 60_000 && it.id == id }
            ?: return@carry null
        saidRefusalMs = r.atMs
        r.why.ifBlank { "the calendar refused it" }
    }

    private val _unheard = MutableStateFlow<String?>(null)

    /**
     * A save's refusal found after the calendar screen that made it had
     * gone, worded by that save: said when the calendar next opens.
     */
    val unheard: StateFlow<String?> = _unheard.asStateFlow()

    /** [refusal] of a save on the calendar screen, kept in [unheard] as [line] words it until [heard]. */
    suspend fun refusalOfSave(id: String, sinceMs: Long, line: (String) -> String): String? = carry {
        refusal(id, sinceMs)?.also { _unheard.value = line(it) }
    }

    /** The calendar screen has said what [unheard] held. */
    fun heard() { _unheard.value = null }

    /**
     * [block] on this repo's scope, not awaited: what a screen started
     * with a write, finished after the screen has gone.
     */
    fun <T> behind(block: suspend () -> T): kotlinx.coroutines.Deferred<T> = scope.async { block() }

    /** Whether "only this one" can go as one edit-at: asked now if not yet known; unknown is no. */
    suspend fun editsAt(): Boolean {
        if (_meetings.value == null) refreshInvites()
        return _meetings.value == true
    }

    /**
     * Answer an invitation: accepted or tentative puts its copy on [cal],
     * declined takes it off, and the host is told. Null when the calendar
     * refused or did not answer ([failedLine] says which). Read back
     * behind it: the invitations, and the window and the month wherever a
     * copy can have come or gone.
     */
    suspend fun answerInvite(key: String, status: String, cal: String): InviteAnswer? = carry {
        val a = api ?: return@carry null
        val had = _invites.value?.firstOrNull { it.key == key }
        // A no to one never placed changes no day: six reads for nothing.
        val reach = Reach(tasks = false, rows = status != "declined" || had == null || had.cal.isNotEmpty(), before = lists())
        val tried = runSuspendCatching { a.answerInvite(key, status, cal) }
        lastWriteError = tried.exceptionOrNull() as? AuspexError
        tried.getOrNull()?.also {
            details.clear()
            scope.launch { refreshInvites(); afterItemWrite(reach) }
        }
    }

    /** Every sync this calendar has, prodded now: Google, followed, shared. */
    suspend fun syncNow(): Boolean = carry {
        val a = api ?: return@carry false
        val kinds = _calendars.value.map { it.kind }.toSet()
        var ok = true
        if ("google" in kinds) ok = tried { a.syncGoogle() } && ok
        if ("caldav" in kinds) ok = tried { a.syncCaldav() } && ok
        if (_shares.value?.accepted.orEmpty().isNotEmpty()) { lastShareSyncMs = nowMs(); ok = tried { a.syncShares() } && ok }
        delay(1500)
        refresh()
        ok
    }

    /**
     * A write that is not an event's, then a full read. On this repo's
     * scope: a share, a revoke or an answer to an offer was made on the
     * calendar screen's, and leaving the screen cancelled it partway.
     */
    private suspend fun after(settleMs: Long = 0, call: suspend (CalendarApi) -> Boolean): Boolean = carry {
        val a = api ?: return@carry false
        val ok = tried { call(a) }
        if (ok) { if (settleMs > 0) delay(settleMs); refresh() }
        ok
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

    /**
     * An event someone shared, as moments, onto [calId] (else the calendar
     * new events go to). False when refused, the calendar's own refusal
     * after its ack included, whose why is kept in [unheard]: "Added" was
     * said of an event the calendar had then refused.
     */
    suspend fun addShared(calId: String?, title: String, startMs: Long, endMs: Long): Boolean = carry {
        val zoneId = _zone.value ?: TimeZone.currentSystemDefault().id
        val zone = runSuspendCatching { TimeZone.of(zoneId) }.getOrElse { TimeZone.currentSystemDefault() }
        val since = nowMs()
        val w = writeEvent(eventBody(sharedDraft(title, startMs, endMs, calId ?: writableDefault(), zone, zoneId)))
        if (!w.ok) return@carry false
        // Before the reading back, which a refused add waits out in full.
        if (refusalOfSave("", since) { "\"$title\" was not added: $it" } != null) {
            w.undo()
            return@carry false
        }
        w.shown.join()
        true
    }

    /** Every event in an .ics onto [calId] (else the calendar new events go to). False when refused. */
    suspend fun importIcs(calId: String?, ics: String): Boolean = carry {
        val a = api ?: return@carry false
        val ok = tried { a.importIcs(calId ?: writableDefault() ?: "default", ics) }
        if (ok) refreshAll()
        ok
    }

    private val _ticking = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /**
     * Ticks on their way to the ship, by task: done or not, as every screen
     * shows the box until the list has the ship's own word. Each screen
     * kept its own, and leaving it lost them: back on the home page a
     * ticked task stood unticked until the write came through and it went.
     */
    val ticking: StateFlow<Map<String, Boolean>> = _ticking.asStateFlow()

    /** Tick or untick a task: in [ticking] at once, and till the list has the ship's word. */
    suspend fun setDone(id: String, done: Boolean): Boolean = carry {
        _ticking.update { it + (id to done) }
        try {
            pokeEvent(doneBody(id, done))
        } finally {
            _ticking.update { it - id }
        }
    }

    /**
     * A new task, on the list at once and written behind it. The write
     * runs here, not in a screen, so leaving the screen does not lose it;
     * the stand-in stays until the refresh after the write brings the
     * calendar's own copy. A refusal takes the stand-in away and says so
     * through [onFailed], the calendar's own after its ack too, which is
     * also kept in [unheard] for a screen that has gone. Not retried: a
     * write whose answer was lost may have landed, and a retry would make
     * it twice.
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
            dueMs = d.due?.let { kotlinx.datetime.LocalDateTime(it.year, it.month.number, it.day, 0, 0).toInstant(TimeZone.UTC).toEpochMilliseconds() },
        )
        _pendingTasks.value = _pendingTasks.value + ghost
        scope.launch {
            val body = eventBody(d)
            val reach = reachOf(body)
            val since = nowMs()
            val ok = write(body)
            // Taken is not kept: the calendar answers before it reads, and
            // a task it then refused stood on the list as added.
            val said = { why: String -> "\"${d.name.trim()}\" was not added: $why" }
            val refused = if (ok) refusalOfSave("", since, said) else null
            if (ok && refused == null) {
                afterItemWrite(reach)
                // A tag the calendar has not seen before joins the list
                // the editor offers; one it has is already there.
                if (d.tags.any { it !in _tags.value }) api?.let { a -> runSuspendCatching { a.tags() }.getOrNull()?.let { _tags.value = it.map { t -> t.tag } } }
            }
            // Why first, then the stand-in goes: the other way round, a
            // screen watching the list saw it leave before it heard why.
            if (!ok) onFailed(failedLine("The ship did not take \"${d.name.trim()}\"."))
            refused?.let { onFailed(said(it)) }
            _pendingTasks.value = _pendingTasks.value - ghost
        }
        return ghost
    }

    /**
     * Any other write, carried on here whatever the screen does. [onFailed]
     * hears why it did not land: not taken, or taken and then refused (kept
     * in [unheard] too), which went unseen and was said as added.
     */
    fun writeInBackground(body: JsonObject, onFailed: (String) -> Unit = {}) {
        scope.launch {
            fun str(k: String) = (body[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
            val name = ((body["meta"] as? JsonObject)?.get("name") as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            val said = { why: String -> "\"$name\" was not ${if (str("action") == "add-event") "added" else "saved"}: $why" }
            val since = nowMs()
            val w = writeEvent(body)
            if (!w.ok) return@launch onFailed(failedLine("The calendar did not take \"$name\"."))
            refusalOfSave(str("id").orEmpty(), since, said)?.let { w.undo(); onFailed(said(it)) }
        }
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
     * edit sat greyed out for minutes. [readBack] false for all but the last
     * of a chain: the second write of an occurrence's edit waited out the
     * first's reading, up to half a minute. False when refused.
     */
    suspend fun pokeEvent(body: JsonObject, readBack: Boolean = true): Boolean =
        writeEvent(body, readBack).let { it.shown.join(); it.ok }

    /**
     * A write the ship has taken or refused ([ok]), and [shown]: done once
     * it has been read back into the lists, or the reading has given up.
     * [undo] takes back what was shown of a write the ship took and the
     * calendar then refused, where no read has put the ship's copy in since.
     */
    class Written(val ok: Boolean, val shown: Job, val undo: () -> Unit = {})

    /**
     * [pokeEvent] that answers as soon as the ship has taken the write, the
     * reading back going on behind it. A save waited for that reading, three
     * requests and more of seconds each on a busy ship, before it said saved;
     * a screen now says so at once and keeps the change on show till then.
     * A task is changed in the task list at once, and put back if refused.
     */
    suspend fun writeEvent(body: JsonObject, readBack: Boolean = true): Written = carry {
        val reach = reachOf(body)
        val putBack = dropDeleted(body)
        // An edit is in the task list at Save, everywhere it shows, and
        // back only if the ship refuses: shown once the ship had answered,
        // it looked unsaved for as long as a busy ship took ("saving a todo
        // edit takes way too long"). A tick or a delete still waits for the
        // ship: a ticked task stays, ticked, until the ship has it done.
        val editing = (body["action"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "edit-event"
        val unassume = if (editing) assumeTask(body) else ({})
        val unmove = assumeEvent(body)
        val ok = write(body)
        val untick = if (ok && !editing) assumeTask(body) else ({})
        if (!ok) { putBack(); unassume(); unmove() }
        val shown = if (ok && readBack) scope.launch { afterItemWrite(reach) } else Job().also { it.complete() }
        Written(ok, shown, undo = if (ok) ({ putBack(); unassume(); unmove(); untick() }) else ({}))
    }

    /**
     * An event's edit, in the window and the month at Save as the ship will
     * have it, as [assumeTask] does for a task; returns what puts the rows
     * back. Only the calendar screen showed it, and the home page's today
     * kept the old event until the reading back came in. A series keeps
     * its occurrences and only its words change here; "only this one"
     * (edit-at) is its one occurrence, moved.
     */
    private fun assumeEvent(body: JsonObject): () -> Unit {
        fun str(k: String) = (body[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
        val id = str("id") ?: return {}
        val at = if (str("action") == "edit-at") str("at_ms")?.toLongOrNull() ?: return {} else null
        if ((at == null && str("action") != "edit-event") || str("cat") == "todo") return {}
        val meta = body["meta"] as? JsonObject ?: return {}
        val span = spanOf(body)
        fun hit(r: CalendarRow) = r.id == id && (at == null || r.l == at)
        fun edited(r: CalendarRow): CalendarRow {
            val moved = span?.takeIf { at != null || !r.repeats }
            return r.copy(
                meta = JsonObject(r.meta.filterKeys { it !in EDITED_META } + meta),
                cal = str("cal") ?: r.cal,
                l = moved?.first ?: r.l, r = moved?.second ?: r.r, all = moved?.third ?: r.all,
            )
        }
        val window = mutableListOf<Pair<CalendarRow, CalendarRow>>()
        val month = mutableListOf<Pair<CalendarRow, CalendarRow>>()
        fun swap(rows: List<CalendarRow>?, kept: MutableList<Pair<CalendarRow, CalendarRow>>): List<CalendarRow>? {
            kept.clear()
            if (rows == null || rows.none(::hit)) return rows
            return rows.map { r -> if (hit(r)) edited(r).also { kept += r to it } else r }.sortedWith(compareBy({ it.l }, { it.r }))
        }
        _rows.update { swap(it, window) }
        _rangeRows.update { swap(it, month) }
        // Each row back only where it is still the one put in: a read since
        // has the ship's own copy, and that stays.
        fun back(rows: List<CalendarRow>?, kept: List<Pair<CalendarRow, CalendarRow>>) =
            if (rows == null || kept.isEmpty()) rows
            else rows.map { r -> kept.firstOrNull { it.second === r }?.first ?: r }.sortedWith(compareBy({ it.l }, { it.r }))
        return { _rows.update { back(it, window) }; _rangeRows.update { back(it, month) } }
    }

    /**
     * Where a one-off event's write puts it, as a row has it: a timed one's
     * wall clock read in its zone (else the calendar's), an all-day one's
     * days in date-space. Null for a series, or a date each year.
     */
    private fun spanOf(body: JsonObject): Triple<Long, Long, Boolean>? {
        fun str(k: String) = (body[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
        if (str("kind") != "once") return null
        val start = str("start_ms")?.toLongOrNull() ?: return null
        return when (str("cat")) {
            "timed" -> {
                val zoneId = str("zone") ?: _zone.value
                val zone = zoneId?.let { runSuspendCatching { TimeZone.of(it) }.getOrNull() } ?: TimeZone.currentSystemDefault()
                val l = kotlin.time.Instant.fromEpochMilliseconds(start).toLocalDateTime(TimeZone.UTC).toInstant(zone).toEpochMilliseconds()
                Triple(l, l + (str("dur_min")?.toLongOrNull() ?: 0L) * 60_000L, false)
            }
            "allday" -> Triple(start, start + (str("span_days")?.toLongOrNull() ?: 1L).coerceAtLeast(1) * 86_400_000L, true)
            else -> null
        }
    }

    /**
     * A task write the ship has taken, in the task list at once as the ship
     * will have it: the list, and the home page's today read from it, moved
     * only once the reading back came in. The reading puts the ship's own
     * copy in its place.
     */
    private fun assumeTask(body: JsonObject): () -> Unit {
        fun str(k: String) = (body[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
        val list = _tasks.value ?: return {}
        val id = str("id") ?: return {}
        val old = list.firstOrNull { it.id == id } ?: return {}
        val new = when (str("action")) {
            "done-event" -> old.copy(done = str("done")?.toBooleanStrictOrNull() ?: return {})
            "del-event" -> null
            "edit-event" -> if (str("cat") != "todo") return {} else old.copy(
                cal = str("cal") ?: old.cal,
                meta = body["meta"] as? JsonObject ?: old.meta,
                dueMs = str("due_ms")?.toLongOrNull(),
                done = body.containsKey("done_ms"),
                // Absent keeps it, as the ship does.
                priority = str("priority")?.toIntOrNull() ?: old.priority,
            )
            else -> return {}
        }
        val at = list.indexOf(old)
        _tasks.update { now -> now?.let { l -> if (new == null) l - old else l.map { if (it.id == id) new else it } } }
        // That task only, unless a read has put the ship's own copy in
        // since: undone as a whole list, any other task changed meanwhile
        // (another edit, a tick) left the list unequal and the refused
        // edit stayed on show.
        return {
            _tasks.update { now ->
                when {
                    now == null -> null
                    new != null -> now.map { if (it === new) old else it }
                    now.any { it.id == id } -> now
                    else -> now.toMutableList().apply { add(at.coerceAtMost(size), old) }
                }
            }
        }
    }

    /**
     * A delete, gone from the window and the month before it is sent;
     * returns what puts them back if the ship refuses it. Only the calendar
     * screen hid a deleted event at once, and the home page's today kept
     * it until the reading back came in, a minute and more on a busy ship.
     */
    private fun dropDeleted(body: JsonObject): () -> Unit {
        val id = (body["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        if ((body["action"] as? kotlinx.serialization.json.JsonPrimitive)?.content != "del-event" || id == null) return {}
        val fromWindow = _rows.value?.filter { it.id == id }.orEmpty()
        val fromMonth = _rangeRows.value?.filter { it.id == id }.orEmpty()
        _rows.update { it?.filterNot { r -> r.id == id } }
        _rangeRows.update { it?.filterNot { r -> r.id == id } }
        // Its rows back, and only where no read has put the ship's own copy
        // in since: undone as whole lists, any other change meanwhile left
        // the refused delete gone from the page.
        fun putBack(rows: List<CalendarRow>?, had: List<CalendarRow>) =
            if (rows == null || had.isEmpty() || rows.any { it.id == id }) rows
            else (rows + had).sortedWith(compareBy({ it.l }, { it.r }))
        return { _rows.update { putBack(it, fromWindow) }; _rangeRows.update { putBack(it, fromMonth) } }
    }

    /**
     * [block] on this repo's scope, awaited. A write made from a screen ran
     * on the screen's scope: leaving it, to see the home page's today, cancelled
     * the write, so on a busy ship an edit never reached the calendar at all.
     * The caller still waits for the answer; leaving only stops the waiting.
     * A failure is caught inside and thrown here: an async that fails takes
     * a scope with a plain Job down with it, and the app's is one.
     */
    suspend fun <T> carry(block: suspend () -> T): T = scope.async { runSuspendCatching { block() } }.await().getOrThrow()

    /**
     * The lists a write can change: [tasks] the task listing, [rows] the
     * window and the month; and all three as they were before it, so a
     * change a routine refresh brought in first still counts as come.
     */
    private class Reach(val tasks: Boolean, val rows: Boolean, val before: Triple<Any?, Any?, Any?>)

    /**
     * What a write of [body] can change, judged before it goes, while the
     * task listing still has the task as it was. The listing only for a
     * task; the window and the month unless a task had no day before and
     * has none after, since an undated task is in no window.
     */
    private fun reachOf(body: JsonObject): Reach {
        val id = (body["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        val cat = (body["cat"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        val was = id?.let { i -> _tasks.value?.firstOrNull { it.id == i } }
        // An id the listing does not have is an event, which is in the windows.
        val wasInRows = id != null && (was == null || was.dueMs != null)
        val isInRows = cat != null && (cat != "todo" || body["due_ms"] != null)
        return Reach(tasks = was != null || cat == "todo", rows = wasInRows || isInRows, before = lists())
    }

    /**
     * A write, then every read a refresh makes: for what changes the
     * calendars themselves, their names, sharing or zone. An event or a
     * task goes through [pokeEvent]. False when refused.
     */
    suspend fun poke(body: JsonObject): Boolean = carry {
        write(body).also { ok ->
            if (ok) {
                delay(APPLY_BEAT_MS)
                refresh()
                range?.let { (f, t) -> loadRange(f, t) }
            }
        }
    }

    /** Why the last write failed, for [failedLine]. */
    // ponytail: the last write's only; two saves failing at once for different reasons share one
    @kotlin.concurrent.Volatile private var lastWriteError: io.nisfeb.talon.mail.AuspexError? = null

    /**
     * What to say of a write that did not land: [refused] when the ship
     * said no, and that the ship is not answering when it never got there.
     * Every failure said the ship would not take the change, a ship down
     * for a restart included.
     */
    fun failedLine(refused: String): String =
        (lastWriteError as? io.nisfeb.talon.mail.AuspexError.Unreachable)?.let { it.said() + " Nothing was changed." } ?: refused

    /**
     * [call], with what stopped it kept for [failedLine]; false when it threw.
     * Only the pokes kept it: a share, a sync or an import read the last
     * poke's, so a ship down for a restart was called a refusal, and a
     * refusal after one said the ship was not answering.
     */
    private suspend fun tried(call: suspend () -> Boolean): Boolean {
        val t = runSuspendCatching { call() }
        lastWriteError = t.exceptionOrNull() as? AuspexError
        return t.getOrDefault(false)
    }

    /** A write and nothing read back: the caller knows what it changed. False when refused. */
    private suspend fun write(body: JsonObject): Boolean {
        val a = api ?: return false
        if (ball.isEmpty()) ball = runSuspendCatching { a.config().ball }.getOrDefault("")
        val ok = tried { a.poke(ball, body) }
        // What was read of an event the write may have changed is no
        // longer what the ship says. Nothing waits here: the beat the
        // nexus needs to apply a poke is the reading's to wait, before it
        // reads; waited here, it held every save on screen.
        if (ok) details.clear()
        return ok
    }

    /**
     * What an event or task write can have changed, read back ([reachOf]):
     * the task listing for a task, the window (the home screen's agenda
     * reads it) and the month on screen for anything with a day. Not the
     * nine reads a full refresh makes: every
     * request into a grubbery app is about a second of its single
     * thread, one behind another, and a tick went through all nine.
     */
    private suspend fun afterItemWrite(reach: Reach) {
        // A task listing nothing has read has nothing to bring up to date.
        val tasks = reach.tasks && reach.before.first != null
        if (!tasks && !reach.rows) { keep(); return }
        // The nexus applies a poke after it answers; give it a beat.
        delay(APPLY_BEAT_MS)
        // The ship answers a write before it applies it, and a busy one
        // applies it seconds later: read once, the old copy came back,
        // the edit's stand-in went, and the change was not shown until
        // the next poll, ten minutes on. So read until something moved,
        // a few times at most, waiting longer each time.
        var pause = 500L
        // Every list is read aside and put in place once it has moved, or
        // at the end: put in on every read, the old copy of a late ship
        // would undo the change assumeTask and dropDeleted showed.
        val a = api
        suspend fun readTasks() = a?.let { runSuspendCatching { it.tasks() }.getOrNull() }
        suspend fun readWindow() = nowMs().let { now -> windowRows(now - BEHIND_MS, now + AHEAD_MS) }
        // The month on screen as the reading began: a page turned since is
        // the screen's own to read.
        val span = range
        suspend fun readMonth() = span?.let { (f, t) -> windowRows(f, t) }
        var got: List<CalendarTask>? = null
        var window: List<CalendarRow>? = null
        var month: List<CalendarRow>? = null
        for (attempt in 1..AFTER_WRITE_READS) {
            if (tasks) got = readTasks() ?: got
            if (reach.rows) {
                window = readWindow() ?: window
                month = readMonth() ?: month
            }
            // Against the lists from before the write: taken after it, a
            // refresh that landed in between made the change look never come.
            val tasksMoved = tasks && got != null && got != reach.before.first
            val windowMoved = reach.rows && window != null && window != reach.before.second
            val monthMoved = reach.rows && month != null && month != reach.before.third
            if (tasksMoved || windowMoved || monthMoved) {
                // It can land between two reads of one pass: the list read
                // first is then the old copy. The home screen's tasks kept a
                // moved one on today while the month showed it on Monday.
                // What came back unchanged is read once more.
                if (tasks && !tasksMoved) got = readTasks() ?: got
                if (reach.rows && !windowMoved) window = readWindow() ?: window
                if (reach.rows && !monthMoved) month = readMonth() ?: month
                break
            }
            if (attempt == AFTER_WRITE_READS) break
            delay(pause)
            pause *= 2
        }
        // The ship's own word, moved or not: a write it never applied goes.
        if (tasks) got?.let { _tasks.value = it }
        window?.let { _rows.value = it }
        if (range == span) month?.let { _rangeRows.value = it }
        // So a phone closed straight after a tick opens on the tick.
        keep()
    }

    private fun lists(): Triple<Any?, Any?, Any?> = Triple(_tasks.value, _rows.value, _rangeRows.value)

    /**
     * Point at the ship's calendar. With [poll] false there is no poller:
     * a headless run reads once with [refresh]. The poller's own first
     * read plus the run's made two full reads in a row, each a dozen
     * requests, and a dictation waited 13 to 30 seconds on them.
     */
    fun attach(baseUrl: String, poll: Boolean = true) {
        if (api != null && shipUrl == baseUrl) return
        shipUrl = baseUrl
        zoneAdopted = false
        zoneNames = null
        details.clear()
        api = CalendarApi(http, baseUrl)
        clearShipState()
        poller?.cancel()
        poller = null
        pollStopped = false
        if (poll) startPoller(readFirst = true)
    }
    private var shipUrl: String? = null

    /** A signed-out answer stopped the poll; the next read that goes through starts it again. */
    @kotlin.concurrent.Volatile private var pollStopped = false

    private fun startPoller(readFirst: Boolean) {
        poller = scope.launch {
            if (readFirst) {
                // What the ship last said, while it is asked again. A month
                // that was right ten minutes ago beats an empty grid.
                restore()
                refresh()
            }
            while (isActive) {
                // Jittered, so devices that started together do not ask together.
                delay(io.nisfeb.talon.urbit.jittered(pollIntervalMs))
                if (foreground && _availability.value != CalendarAvailability.SIGNED_OUT) refreshIfStale(0)
            }
        }
    }

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
        _invites.value = null
        _meetings.value = null
        invitesReadMs = 0
        _tags.value = emptyList()
        _zone.value = null
        _leadMin.value = null
        _remindersKnown.value = false
        _conflicts.value = emptyList()
        _sync.value = emptyMap()
        _error.value = null
        ball = ""
        lastShareSyncMs = 0
    }

    fun detach() {
        poller?.cancel()
        poller = null
        pollStopped = false
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
            // Not on every alt-tab: a full read is up to nine requests of
            // the ship's one thread, about a second each.
            refreshIfStale(FOCUS_FRESH_MS)
        }
    }

    /** When the events and tasks, and when everything, was last read. */
    @kotlin.concurrent.Volatile private var lastReadMs = 0L
    @kotlin.concurrent.Volatile private var lastFullMs = 0L

    /**
     * Read again what may have moved: everything past an hour (the
     * calendars, shares, sync, tags and config change rarely), else the
     * events and tasks past [freshMs], else nothing. Focus, the poll and
     * opening the calendar each ran a full read, every time.
     */
    suspend fun refreshIfStale(freshMs: Long) {
        val now = nowMs()
        when {
            now - lastFullMs > FULL_READ_EVERY_MS -> refresh()
            now - lastReadMs >= freshMs -> refreshLight()
        }
    }

    /** The events and tasks: two requests, where a full read is up to nine. */
    suspend fun refreshLight() = refresh(full = false)

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
            _error.value = (e as? AuspexError)?.said() ?: "The calendar could not be read."
        }
    }

    /**
     * Everything, or with [full] false only the events and tasks: the
     * calendars, shares, sync, tags and config change rarely. With
     * [rarely] false a full read leaves out shares, sync state and tags:
     * what a headless run's tools read, in half the requests.
     */
    suspend fun refresh(full: Boolean = true, rarely: Boolean = true) = gate.withLock {
        val a = api ?: return@withLock
        try {
            val now = nowMs()
            val w = a.window(now - BEHIND_MS, now + AHEAD_MS)
            _rows.value = w.rows.sortedWith(compareBy({ it.l }, { it.r }))
            // The ship has just said what is current, so nothing read of
            // one event before now is: the assistant reads through here too.
            details.clear()
            if (full) _calendars.value = runSuspendCatching { a.calendars() }.getOrNull() ?: _calendars.value
            _tasks.value = runSuspendCatching { a.tasks() }.getOrNull() ?: _tasks.value
            if (full && rarely) readRarely(a)
            keep()
            val config = if (full) runSuspendCatching { a.config() }.getOrNull() else null
            config?.let { _zone.value = it.zone; ball = it.ball; _leadMin.value = it.leadMin }
            noteReminders(w.rows)
            _availability.value = CalendarAvailability.PRESENT
            _error.value = null
            lastReadMs = now
            if (full) {
                lastFullMs = now
                if (config != null && config.zone == null) adoptZoneIfNone()
            }
            // A 403 stopped the poll; signed in again, this read is the first
            // to go through, and nothing else would start it again.
            if (pollStopped) { pollStopped = false; startPoller(readFirst = false) }
        } catch (e: AuspexError) {
            readFailed(e)
        }
    }

    /** What a full read adds between the tasks and the config: shares, sync state and tags. */
    private suspend fun readRarely(a: CalendarApi) {
        // Null only when the calendar has no sharing (404); a hiccup
        // keeps the last answer, and with it the read-only guard.
        _shares.value = runSuspendCatching { a.shares() }.getOrElse { e ->
            if (e is AuspexError.Refused && e.status == AuspexApi.NOT_FOUND) null else _shares.value
        }
        // Sync state and conflicts only where a calendar syncs: three
        // reads of the ship's one queue that say nothing of local ones.
        val syncing = _calendars.value.any { it.kind != "local" }
        _conflicts.value = if (syncing) runSuspendCatching { a.conflicts() }.getOrElse { _conflicts.value } else emptyList()
        _sync.value = buildMap {
            if (syncing) {
                runSuspendCatching { a.google() }.getOrNull()?.linked?.forEach { (id, row) -> put(id, row) }
                runSuspendCatching { a.caldavSubscriptions() }.getOrNull()?.forEach { put(it.id, SyncRow(it.lastMs, it.error)) }
            }
            _shares.value?.accepted?.forEach { (id, acc) -> put(id, SyncRow(acc.lastMs, acc.error)) }
        }.ifEmpty { if (_calendars.value.any { it.kind != "local" }) _sync.value else emptyMap() }
        _tags.value = runSuspendCatching { a.tags() }.getOrNull()?.map { it.tag } ?: _tags.value
    }

    private fun readFailed(e: AuspexError) {
        when {
            e.isSignedOut -> {
                _availability.value = CalendarAvailability.SIGNED_OUT
                poller?.let { it.cancel(); poller = null; pollStopped = true }
            }
            e is AuspexError.Refused && e.status == AuspexApi.NOT_FOUND ->
                _availability.value = CalendarAvailability.ABSENT
            else -> {
                _error.value = e.said()
                Log.w(TAG, "calendar refresh failed", e)
            }
        }
    }

    companion object {
        /** How long the nexus takes to apply a poke it has answered, read after. */
        const val APPLY_BEAT_MS = 400L

        /** How long after a write the calendar's own page looks for its refusal. */
        const val REFUSAL_WAIT_MS = 1_500L

        private const val TAG = "CalendarRepo"
        const val BEHIND_MS = 6 * 60 * 60 * 1000L
        const val AHEAD_MS = 30L * 24 * 60 * 60 * 1000L
        const val SHARE_SYNC_GAP_MS = 5 * 60 * 1000L
        /** A read on coming back only past this. */
        const val FOCUS_FRESH_MS = 3 * 60 * 1000L
        /** A read on opening the calendar only past this. */
        const val OPEN_FRESH_MS = 60 * 1000L
        /** The calendars, shares, sync, tags and config, read whole this often. */
        const val FULL_READ_EVERY_MS = 60 * 60 * 1000L
        /** Read-backs after a write before taking the ship at its word: about 30s of waiting. */
        const val AFTER_WRITE_READS = 6
    }
}
