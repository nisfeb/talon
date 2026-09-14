package io.nisfeb.talon.calendar

import io.ktor.client.HttpClient
import io.nisfeb.talon.mail.AuspexApi
import io.nisfeb.talon.mail.AuspexError
import io.nisfeb.talon.mail.isSignedOut
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
import kotlinx.serialization.json.JsonObject

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
    private val _tags = MutableStateFlow<List<String>>(emptyList())
    /** Every tag in use on the ship's calendar, for a filter. */
    val tags: StateFlow<List<String>> = _tags.asStateFlow()
    private val _zone = MutableStateFlow<String?>(null)
    /** The calendar's display zone, or null for the device's. */
    val zone: StateFlow<String?> = _zone.asStateFlow()
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
        if (api != null && api?.let { true } == true && shipUrl == baseUrl) return
        shipUrl = baseUrl
        api = CalendarApi(http, baseUrl)
        _availability.value = CalendarAvailability.UNKNOWN
        _rows.value = null
        _error.value = null
        poller?.cancel()
        poller = scope.launch {
            refresh()
            while (isActive) {
                delay(pollIntervalMs)
                if (foreground && _availability.value != CalendarAvailability.SIGNED_OUT) refresh()
            }
        }
    }
    private var shipUrl: String? = null

    fun detach() {
        poller?.cancel()
        poller = null
        api = null
        shipUrl = null
        _availability.value = CalendarAvailability.UNKNOWN
        _rows.value = null
        _error.value = null
    }

    fun setForeground(on: Boolean) {
        val was = foreground
        foreground = on
        if (on && !was) scope.launch { refresh() }
    }

    suspend fun refresh() = gate.withLock {
        val a = api ?: return@withLock
        try {
            val now = nowMs()
            val w = a.window(now - BEHIND_MS, now + AHEAD_MS)
            _rows.value = w.rows.sortedWith(compareBy({ it.l }, { it.r }))
            _calendars.value = runCatching { a.calendars() }.getOrDefault(emptyList())
            _tasks.value = runCatching { a.tasks() }.getOrNull() ?: _tasks.value
            _tags.value = runCatching { a.tags() }.getOrDefault(emptyList()).map { it.tag }
            runCatching { a.config() }.getOrNull()?.let { _zone.value = it.zone; ball = it.ball }
            _availability.value = CalendarAvailability.PRESENT
            _error.value = null
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
    }
}
