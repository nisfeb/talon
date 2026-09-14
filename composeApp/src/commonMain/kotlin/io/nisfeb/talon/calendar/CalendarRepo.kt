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
    private val _zone = MutableStateFlow<String?>(null)
    /** The calendar's display zone, or null for the device's. */
    val zone: StateFlow<String?> = _zone.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

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
            _zone.value = runCatching { a.config().zone }.getOrNull()
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
