package io.nisfeb.talon.ai

import io.nisfeb.talon.ui.JvmUiSettingsStore
import io.nisfeb.talon.ui.StoredWatchwordsSyncSettings
import io.nisfeb.talon.util.AppDirs
import java.io.File

/**
 * JSON-file-backed watchwords-sync toggle for desktop. Stored next
 * to a JSON file in the platform user-data dir. Atomic writes
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
    file: File = File(AppDirs.userData, "watchwords_sync.json"),
) : WatchwordsSyncSettings by StoredWatchwordsSyncSettings(JvmUiSettingsStore(file))
