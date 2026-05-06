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
 * JSON-file-backed daily digest settings for desktop. Stored in the
 * platform-standard user-data dir (see [AppDirs]). Atomic writes
 * (temp + ATOMIC_MOVE) to survive JVM crashes mid-write.
 */
class DesktopDailyDigestSettings(
    // Injectable for tests. Production callers use the default which
    // resolves to ~/.config/talon/daily_digest.json (or its platform
    // equivalent via AppDirs).
    private val file: File = File(AppDirs.userData, "daily_digest.json"),
) : DailyDigestSettings {
    @Serializable
    private data class Persisted(
        // Matches DailyDigestSettings.State default — true so a fresh
        // install (or a partial JSON parse) lands on the morning brief
        // already wired up.
        val enabled: Boolean = true,
        val hourOfDay: Int = 6,
        val minuteOfDay: Int = 0,
    )

    private val _state = MutableStateFlow(loadInitial())
    override val state: StateFlow<DailyDigestSettings.State> = _state.asStateFlow()

    @Volatile
    override var onChange: ((DailyDigestSettings.Change, Boolean) -> Unit)? = null

    private fun loadInitial(): DailyDigestSettings.State {
        if (!file.exists()) return DailyDigestSettings.State()
        return runCatching {
            val p = JSON.decodeFromString<Persisted>(file.readText())
            DailyDigestSettings.State(p.enabled, p.hourOfDay, p.minuteOfDay)
        }.getOrElse { DailyDigestSettings.State() }
    }

    private fun persist(s: DailyDigestSettings.State) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSON.encodeToString(Persisted(s.enabled, s.hourOfDay, s.minuteOfDay)))
        Files.move(
            tmp.toPath(), file.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        _state.value = s
    }

    override fun setEnabled(enabled: Boolean) {
        if (_state.value.enabled == enabled) return
        persist(_state.value.copy(enabled = enabled))
        onChange?.invoke(DailyDigestSettings.Change.Toggled, false)
    }

    override fun setTime(hourOfDay: Int, minuteOfDay: Int) {
        require(hourOfDay in 0..23) { "hourOfDay must be 0..23" }
        require(minuteOfDay in 0..59) { "minuteOfDay must be 0..59" }
        val cur = _state.value
        if (cur.hourOfDay == hourOfDay && cur.minuteOfDay == minuteOfDay) return
        persist(cur.copy(hourOfDay = hourOfDay, minuteOfDay = minuteOfDay))
        onChange?.invoke(DailyDigestSettings.Change.TimeChanged, false)
    }

    override fun applyRemote(enabled: Boolean, hourOfDay: Int, minuteOfDay: Int) {
        persist(DailyDigestSettings.State(enabled, hourOfDay, minuteOfDay))
    }

    override fun emitSyncToggledOff() {
        onChange?.invoke(DailyDigestSettings.Change.SyncToggledOff, true)
    }

    private companion object {
        private val JSON = Json { ignoreUnknownKeys = true; prettyPrint = false }
    }
}
