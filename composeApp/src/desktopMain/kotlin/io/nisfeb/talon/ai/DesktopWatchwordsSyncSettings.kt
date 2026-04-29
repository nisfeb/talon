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
 */
class DesktopWatchwordsSyncSettings : WatchwordsSyncSettings {
    @Serializable
    private data class Persisted(val enabled: Boolean = false)

    private val file: File by lazy { File(AppDirs.userData, "watchwords_sync.json") }

    private val _enabled = MutableStateFlow(loadInitial())
    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    override fun setEnabled(value: Boolean) {
        if (_enabled.value == value) return
        persist(value)
        _enabled.value = value
    }

    private fun loadInitial(): Boolean {
        if (!file.exists()) return false
        return runCatching {
            JSON.decodeFromString<Persisted>(file.readText()).enabled
        }.getOrElse { false }
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
