package io.nisfeb.talon.ui

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
 * JSON-file-backed UI settings for desktop. Sits next to the other
 * per-process settings files in the user-data dir. Atomic-move write
 * so a JVM crash mid-write can't truncate the file to an unparseable
 * state.
 */
class DesktopUiSettings : UiSettings {
    @Serializable
    private data class Persisted(val hideComposerButtons: Boolean = false)

    private val file: File by lazy { File(AppDirs.userData, "ui.json") }

    private val _hideComposerButtons = MutableStateFlow(loadInitial())
    override val hideComposerButtons: StateFlow<Boolean> =
        _hideComposerButtons.asStateFlow()

    override fun setHideComposerButtons(hidden: Boolean) {
        if (_hideComposerButtons.value == hidden) return
        persist(Persisted(hidden))
        _hideComposerButtons.value = hidden
    }

    private fun loadInitial(): Boolean {
        if (!file.exists()) return false
        return runCatching {
            JSON.decodeFromString<Persisted>(file.readText()).hideComposerButtons
        }.getOrElse { false }
    }

    private fun persist(value: Persisted) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSON.encodeToString(value))
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
