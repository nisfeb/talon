package io.nisfeb.talon.ui

import io.nisfeb.talon.util.AppDirs
import java.io.File

/**
 * JSON-file-backed [MenuSeenStore] for desktop. Per-ship file in the
 * user-data dir so switching ships doesn't bleed seen-state across.
 * Atomic-move write so a JVM crash mid-write can't truncate the
 * file. Mirrors [DesktopUiSettings].
 */
class DesktopMenuSeenStore(
    ship: String,
    file: File = defaultFile(ship),
) : MenuSeenStore by StoredMenuSeenStore(JvmUiSettingsStore(file)) {
    internal companion object {
        fun defaultFile(ship: String): File = File(AppDirs.userData, menuSeenFileName(ship))
    }
}
