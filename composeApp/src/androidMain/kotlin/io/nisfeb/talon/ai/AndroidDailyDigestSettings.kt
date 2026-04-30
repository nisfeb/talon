package io.nisfeb.talon.ai

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android implementation of [DailyDigestSettings] — backed by
 * SharedPreferences. The desktop counterpart is the JSON-file
 * [DesktopDailyDigestSettings] in desktopMain.
 */
class AndroidDailyDigestSettings(context: Context) : DailyDigestSettings {

    @Volatile
    override var onChange: ((DailyDigestSettings.Change, transitionedOffSync: Boolean) -> Unit)? = null

    private val prefs: SharedPreferences =
        context.getSharedPreferences("talon_daily_digest", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    override val state: StateFlow<DailyDigestSettings.State> = _state.asStateFlow()

    override fun setEnabled(enabled: Boolean) {
        if (_state.value.enabled == enabled) return
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _state.value = _state.value.copy(enabled = enabled)
        onChange?.invoke(DailyDigestSettings.Change.Toggled, false)
    }

    override fun setTime(hourOfDay: Int, minuteOfDay: Int) {
        require(hourOfDay in 0..23) { "hourOfDay must be 0..23" }
        require(minuteOfDay in 0..59) { "minuteOfDay must be 0..59" }
        if (_state.value.hourOfDay == hourOfDay && _state.value.minuteOfDay == minuteOfDay) return
        prefs.edit()
            .putInt(KEY_HOUR, hourOfDay)
            .putInt(KEY_MINUTE, minuteOfDay)
            .apply()
        _state.value = _state.value.copy(hourOfDay = hourOfDay, minuteOfDay = minuteOfDay)
        onChange?.invoke(DailyDigestSettings.Change.TimeChanged, false)
    }

    override fun applyRemote(enabled: Boolean, hourOfDay: Int, minuteOfDay: Int) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putInt(KEY_HOUR, hourOfDay)
            .putInt(KEY_MINUTE, minuteOfDay)
            .apply()
        _state.value = DailyDigestSettings.State(
            enabled = enabled,
            hourOfDay = hourOfDay,
            minuteOfDay = minuteOfDay,
        )
    }

    override fun emitSyncToggledOff() {
        onChange?.invoke(DailyDigestSettings.Change.SyncToggledOff, true)
    }

    private fun read(): DailyDigestSettings.State = DailyDigestSettings.State(
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
