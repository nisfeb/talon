package io.nisfeb.talon.ui.theme

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-device override for the app's color scheme. Three modes:
 * [Mode.System] follows the OS, [Mode.Light] forces the light scheme,
 * [Mode.Dark] forces the dark scheme. Production Android can wire its
 * SharedPreferences-backed copy when composeApp replaces app/.
 */
interface ThemePreference {
    enum class Mode { System, Light, Dark }

    val mode: StateFlow<Mode>
    fun setMode(mode: Mode)
}

class InMemoryThemePreference(initial: ThemePreference.Mode = ThemePreference.Mode.System) :
    ThemePreference {
    private val _mode = MutableStateFlow(initial)
    override val mode: StateFlow<ThemePreference.Mode> = _mode.asStateFlow()
    override fun setMode(mode: ThemePreference.Mode) {
        _mode.value = mode
    }
}
