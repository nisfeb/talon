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
private object IosUiSettingsStore : UiSettingsStore {
    private const val FILE = "ui.json"
    override fun read(): String? = IosFiles.read(FILE)
    override fun write(text: String) = IosFiles.write(FILE, text)
}

/** A JSON file in Application Support, beside ui.json. */
internal class IosTextStore(private val file: String) : UiSettingsStore {
    override fun read(): String? = IosFiles.read(file)
    override fun write(text: String) = IosFiles.write(file, text)
}

fun createUiSettings(db: AppDatabase, scope: CoroutineScope): UiSettings =
    FileUiSettings(IosUiSettingsStore, db, scope)
