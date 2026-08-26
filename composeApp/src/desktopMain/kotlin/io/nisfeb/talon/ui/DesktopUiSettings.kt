package io.nisfeb.talon.ui

import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.util.AppDirs
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Desktop UI settings: [FileUiSettings] over a java.nio store in the
 * user-data dir, next to the other per-process settings files. The
 * write is an atomic move so a JVM crash mid-write can't leave a
 * truncated, unparseable file.
 */
private class JvmUiSettingsStore(private val file: File) : UiSettingsStore {
    override fun read(): String? = if (file.exists()) file.readText() else null

    override fun write(text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        Files.move(
            tmp.toPath(), file.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
}

fun DesktopUiSettings(
    db: AppDatabase,
    scope: CoroutineScope,
    file: File = File(AppDirs.userData, "ui.json"),
): UiSettings = FileUiSettings(JvmUiSettingsStore(file), db, scope)
