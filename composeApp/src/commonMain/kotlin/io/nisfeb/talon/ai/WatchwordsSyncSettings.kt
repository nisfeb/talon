package io.nisfeb.talon.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-device "mirror watchwords to %settings" toggle. Owns its own
 * StateFlow so the screen can observe and the App-level wiring can
 * point push/clear paths at the same source of truth.
 *
 * Why an interface: production Android backs this with SharedPreferences
 * via TalonApplication; desktop persists to a JSON file (see
 * DesktopWatchwordsSyncSettings); tests get the in-memory default.
 */
interface WatchwordsSyncSettings {
    val enabled: StateFlow<Boolean>
    fun setEnabled(value: Boolean)
}

class InMemoryWatchwordsSyncSettings(initial: Boolean = true) : WatchwordsSyncSettings {
    private val _enabled = MutableStateFlow(initial)
    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    override fun setEnabled(value: Boolean) {
        _enabled.value = value
    }
}
