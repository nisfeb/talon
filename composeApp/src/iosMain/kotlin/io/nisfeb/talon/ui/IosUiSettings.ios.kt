package io.nisfeb.talon.ui

import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.util.IosFiles
import kotlinx.coroutines.CoroutineScope

/**
 * iOS UI settings: [FileUiSettings] over a JSON file in Application
 * Support, the same place the session, theme and assistant settings live.
 *
 * Before this, iOS fell through to [InMemoryUiSettings], so every
 * per-device preference — naming style, accent, density, rail order —
 * silently reset on each launch.
 */
/**
 * A JSON file in Application Support, as ui.json is. Read while the
 * device is locked it is nothing, so a write is refused until a read has
 * worked: the settings in memory were never the file's.
 */
internal class IosTextStore(name: String) : UiSettingsStore {
    private val file = io.nisfeb.talon.util.LockableFile(
        name,
        readText = { IosFiles.read(name) },
        exists = { IosFiles.exists(name) },
        writeText = { IosFiles.write(name, it) },
    )
    override fun read(): String? = file.text()
    override fun write(text: String) { file.write(text) }
}

fun createUiSettings(db: AppDatabase, scope: CoroutineScope): UiSettings =
    FileUiSettings(IosTextStore("ui.json"), db, scope)
