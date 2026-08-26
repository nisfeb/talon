package io.nisfeb.talon.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Knobs for the daily digest alarm. Controls whether it fires at all
 * and at what time. The AI summary opt-in lives in
 * [AiSettings.Config.dailyDigestEnabled] because it composes with
 * the rest of the AI toggles.
 *
 * Mirrored to the %settings bucket "daily-digest". Production Android's
 * implementation is SharedPreferences-backed; desktop's is a JSON file
 * via [DesktopDailyDigestSettings]. composeApp uses neither today —
 * the daily digest doesn't run on desktop (no AlarmManager equivalent
 * wired) — but [SettingsSync] still needs the interface to apply
 * remote events that other devices push.
 */
interface DailyDigestSettings {

    data class State(
        // Default-on so a fresh install starts with the morning brief
        // wired up at 6:00 without an explicit opt-in. Concrete impls
        // (AndroidDailyDigestSettings) match this default — explicit
        // user-off persists across reinstalls.
        val enabled: Boolean = true,
        val hourOfDay: Int = 6,
        val minuteOfDay: Int = 0,
    )

    sealed class Change {
        data object Toggled : Change()
        data object TimeChanged : Change()
        data object SyncToggledOff : Change()
    }

    val state: StateFlow<State>

    var onChange: ((Change, transitionedOffSync: Boolean) -> Unit)?

    fun setEnabled(enabled: Boolean)
    fun setTime(hourOfDay: Int, minuteOfDay: Int)

    /** Apply remote settings (incoming from %settings sync) without
     *  triggering [onChange] — avoids ping-pong back to the ship. */
    fun applyRemote(enabled: Boolean, hourOfDay: Int, minuteOfDay: Int)

    fun emitSyncToggledOff()
}

/**
 * Digest-less stand-in for platforms that have no scheduler to fire a
 * brief (iOS, and desktop's tests). Holds the default state, accepts
 * writes, and tells nobody — the feature is gated off by
 * [io.nisfeb.talon.ui.isDailyDigestSupported] on those platforms, so
 * this only exists to keep %settings wiring total.
 */
class NoopDailyDigestSettings : DailyDigestSettings {
    private val _state = MutableStateFlow(DailyDigestSettings.State())
    override val state: StateFlow<DailyDigestSettings.State> = _state
    override var onChange: ((DailyDigestSettings.Change, Boolean) -> Unit)? = null
    override fun setEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(enabled = enabled)
    }
    override fun setTime(hourOfDay: Int, minuteOfDay: Int) {
        _state.value = _state.value.copy(hourOfDay = hourOfDay, minuteOfDay = minuteOfDay)
    }
    override fun applyRemote(enabled: Boolean, hourOfDay: Int, minuteOfDay: Int) {
        _state.value = DailyDigestSettings.State(enabled, hourOfDay, minuteOfDay)
    }
    override fun emitSyncToggledOff() {}
}
