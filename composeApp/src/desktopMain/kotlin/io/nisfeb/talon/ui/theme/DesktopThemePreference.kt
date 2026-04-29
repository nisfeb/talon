package io.nisfeb.talon.ui.theme

import io.nisfeb.talon.util.AppDirs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * JSON-file-backed theme preference for desktop. Stored alongside
 * other per-process settings in the platform user-data dir. Atomic
 * writes (temp + ATOMIC_MOVE) so a JVM crash mid-write can't truncate
 * the file to an unparseable state.
 */
class DesktopThemePreference(
    private val file: File = File(AppDirs.userData, "theme.json"),
) : ThemePreference {
    @Serializable
    private data class Persisted(val mode: String = ThemePreference.Mode.System.name)

    private val _mode = MutableStateFlow(loadInitial())
    override val mode: StateFlow<ThemePreference.Mode> = _mode.asStateFlow()

    override fun setMode(mode: ThemePreference.Mode) {
        if (_mode.value == mode) return
        persist(mode)
        _mode.value = mode
    }

    private fun loadInitial(): ThemePreference.Mode {
        if (!file.exists()) return ThemePreference.Mode.System
        return runCatching {
            val name = JSON.decodeFromString<Persisted>(file.readText()).mode
            ThemePreference.Mode.valueOf(name)
        }.getOrElse { ThemePreference.Mode.System }
    }

    private fun persist(mode: ThemePreference.Mode) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSON.encodeToString(Persisted(mode.name)))
        Files.move(
            tmp.toPath(), file.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private companion object {
        private val JSON = Json { ignoreUnknownKeys = true; prettyPrint = false }
    }
}
