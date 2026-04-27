package io.nisfeb.talon.ai

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SharedPreferences-backed knobs for the daily digest alarm.
 * Controls whether the alarm fires at all, and at what time.
 *
 * The AI summary opt-in lives in [AiSettings.Config.dailyDigestEnabled]
 * because it composes with the rest of the AI feature toggles.
 *
 * Mirrored to %settings bucket "daily-digest" (see spec §Settings sync).
 */
class DailyDigestSettings(context: Context) {

    data class State(
        val enabled: Boolean = false,
        val hourOfDay: Int = 6,
        val minuteOfDay: Int = 0,
    )

    sealed class Change {
        data object Toggled : Change()
        data object TimeChanged : Change()
        data object SyncToggledOff : Change()
    }

    @Volatile var onChange: ((Change, transitionedOffSync: Boolean) -> Unit)? = null

    private val prefs: SharedPreferences =
        context.getSharedPreferences("talon_daily_digest", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<State> = _state.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        if (_state.value.enabled == enabled) return
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _state.value = _state.value.copy(enabled = enabled)
        onChange?.invoke(Change.Toggled, false)
    }

    fun setTime(hourOfDay: Int, minuteOfDay: Int) {
        require(hourOfDay in 0..23) { "hourOfDay must be 0..23" }
        require(minuteOfDay in 0..59) { "minuteOfDay must be 0..59" }
        if (_state.value.hourOfDay == hourOfDay && _state.value.minuteOfDay == minuteOfDay) return
        prefs.edit()
            .putInt(KEY_HOUR, hourOfDay)
            .putInt(KEY_MINUTE, minuteOfDay)
            .apply()
        _state.value = _state.value.copy(hourOfDay = hourOfDay, minuteOfDay = minuteOfDay)
        onChange?.invoke(Change.TimeChanged, false)
    }

    /**
     * Apply remote settings (incoming from `%settings` sync). Same idiom
     * AiSettings uses — bypasses the [onChange] mirror to avoid
     * loop-back to the ship.
     */
    fun applyRemote(enabled: Boolean, hourOfDay: Int, minuteOfDay: Int) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putInt(KEY_HOUR, hourOfDay)
            .putInt(KEY_MINUTE, minuteOfDay)
            .apply()
        _state.value = State(enabled = enabled, hourOfDay = hourOfDay, minuteOfDay = minuteOfDay)
    }

    fun emitSyncToggledOff() {
        onChange?.invoke(Change.SyncToggledOff, true)
    }

    private fun read(): State = State(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        hourOfDay = prefs.getInt(KEY_HOUR, 6),
        minuteOfDay = prefs.getInt(KEY_MINUTE, 0),
    )

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_HOUR = "hour_of_day"
        const val KEY_MINUTE = "minute_of_day"
    }
}
