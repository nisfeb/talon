package io.nisfeb.talon.notify

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Observable diagnostics for "are notifications actually getting
 * through to me." Drives the Settings → Notification Health panel
 * and feeds future ship-side telemetry. Populated by TlonChatRepo
 * (SSE / reconcile timestamps) and the Android-side service
 * (foreground-service uptime, battery-optimization status).
 *
 * Per-process singleton in spirit — App.kt holds one instance and
 * passes it down. Not a Compose CompositionLocal because non-UI
 * call sites (the foreground service, the WorkManager worker)
 * also need to write to it.
 */
class NotificationHealth {
    /**
     * Last time the SSE channel delivered any event (ms since
     * epoch). 0 means "never since this process started" — note
     * this resets across process restarts; pair with
     * [lastReconcileMs] for "have we ever talked to the ship."
     */
    private val _lastSseEventMs = MutableStateFlow(0L)
    val lastSseEventMs: StateFlow<Long> = _lastSseEventMs.asStateFlow()
    fun markSseEvent(ms: Long = System.currentTimeMillis()) {
        _lastSseEventMs.value = ms
    }

    /**
     * Last time a successful catchUp / bootstrap completed. Anchors
     * "your local state is up to date" — this is the timestamp the
     * health panel surfaces as "Last sync."
     */
    private val _lastReconcileMs = MutableStateFlow(0L)
    val lastReconcileMs: StateFlow<Long> = _lastReconcileMs.asStateFlow()
    fun markReconcileSuccess(ms: Long = System.currentTimeMillis()) {
        _lastReconcileMs.value = ms
    }

    /**
     * SSE channel state — true while we're consuming from a live
     * collect loop; false during reconnect, cold start, or stop.
     * Drives the panel's "currently live / reconnecting" indicator.
     */
    private val _sseConnected = MutableStateFlow(false)
    val sseConnected: StateFlow<Boolean> = _sseConnected.asStateFlow()
    fun markSseConnected(connected: Boolean) {
        _sseConnected.value = connected
    }

    /**
     * Number of times the watchdog had to force-reconnect because
     * SSE went silent for >90s. Cheap signal that the user's network
     * or the ship's eventbus is flaky — non-zero is informational,
     * sustained growth is a red flag for the health panel.
     */
    private val _forceReconnects = MutableStateFlow(0)
    val forceReconnects: StateFlow<Int> = _forceReconnects.asStateFlow()
    fun incrementForceReconnects() {
        _forceReconnects.value += 1
    }

    /**
     * Cumulative "events recovered by reconcile" — a non-zero value
     * means at least one event arrived from a non-SSE path
     * (foreground catch-up, network-change recover, periodic worker).
     * If this stays at 0 over time, the layered architecture is
     * never paying off; if it's growing, it's actively saving the
     * user from missed notifications.
     */
    private val _recoveredEvents = MutableStateFlow(0)
    val recoveredEvents: StateFlow<Int> = _recoveredEvents.asStateFlow()
    fun addRecoveredEvents(count: Int) {
        if (count > 0) _recoveredEvents.value += count
    }
}
