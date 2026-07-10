package io.nisfeb.talon.ui.theme

import io.nisfeb.talon.util.IosFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val THEME_FILE = "theme.json"

@Serializable
private data class ThemePersisted(val mode: String = ThemePreference.Mode.System.name)

/** iOS light/dark/system preference, persisted to theme.json. */
class IosThemePreference : ThemePreference {
    private val json = Json { ignoreUnknownKeys = true }
    private val _mode = MutableStateFlow(load())
    override val mode: StateFlow<ThemePreference.Mode> = _mode.asStateFlow()

    private fun load(): ThemePreference.Mode {
        val persisted = IosFiles.read(THEME_FILE)?.let {
            runCatching { json.decodeFromString<ThemePersisted>(it) }.getOrNull()
        } ?: ThemePersisted()
        return runCatching { ThemePreference.Mode.valueOf(persisted.mode) }
            .getOrDefault(ThemePreference.Mode.System)
    }

    override fun setMode(mode: ThemePreference.Mode) {
        if (_mode.value == mode) return
        _mode.value = mode
        IosFiles.write(
            THEME_FILE,
            json.encodeToString(ThemePersisted.serializer(), ThemePersisted(mode.name)),
        )
    }
}
