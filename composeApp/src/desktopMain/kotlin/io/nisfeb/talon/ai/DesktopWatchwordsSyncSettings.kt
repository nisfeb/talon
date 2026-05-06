package io.nisfeb.talon.ai

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
 * JSON-file-backed watchwords-sync toggle for desktop. Stored next
 * to daily_digest.json in the platform user-data dir. Atomic writes
 * (temp + ATOMIC_MOVE) so a JVM crash mid-write can't truncate the
 * file to an unparseable state.
 *
 * Default is `true` (sync enabled). The file only exists once the
 * user has explicitly toggled — so an absent or corrupt file falls
 * back to the new default and the user gets cross-device watchwords
 * out of the box. Users who explicitly toggled off keep that choice
 * because the file persists with `enabled = false`.
 */
class DesktopWatchwordsSyncSettings(
    private val file: File = File(AppDirs.userData, "watchwords_sync.json"),
) : WatchwordsSyncSettings {
    @Serializable
    private data class Persisted(val enabled: Boolean = true)

    private val _enabled = MutableStateFlow(loadInitial())
    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    override fun setEnabled(value: Boolean) {
        if (_enabled.value == value) return
        persist(value)
        _enabled.value = value
    }

    private fun loadInitial(): Boolean {
        if (!file.exists()) return true
        return runCatching {
            JSON.decodeFromString<Persisted>(file.readText()).enabled
        }.getOrElse { true }
    }

    private fun persist(value: Boolean) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSON.encodeToString(Persisted(value)))
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
