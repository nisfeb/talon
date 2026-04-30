package io.nisfeb.talon.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SharedPreferences-backed [ThemePreference] for Android. Persists
 * the user's theme override across process death — the desktop
 * counterpart is [DesktopThemePreference]. Pre-Stage-F, the legacy
 * SettingsScreen managed this state internally; lifted here so the
 * canonical commonMain SettingsScreen can drive it via a slot.
 */
class AndroidThemePreference(context: Context) : ThemePreference {
    private val prefs = context.getSharedPreferences("talon.theme", Context.MODE_PRIVATE)

    private val _mode = MutableStateFlow(loadInitial())
    override val mode: StateFlow<ThemePreference.Mode> = _mode.asStateFlow()

    override fun setMode(mode: ThemePreference.Mode) {
        if (_mode.value == mode) return
        prefs.edit().putString(KEY_MODE, mode.name).apply()
        _mode.value = mode
    }

    private fun loadInitial(): ThemePreference.Mode {
        val name = prefs.getString(KEY_MODE, null) ?: return ThemePreference.Mode.System
        return runCatching { ThemePreference.Mode.valueOf(name) }
            .getOrElse { ThemePreference.Mode.System }
    }

    private companion object {
        private const val KEY_MODE = "mode"
    }
}
