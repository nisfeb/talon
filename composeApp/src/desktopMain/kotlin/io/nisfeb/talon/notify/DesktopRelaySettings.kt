package io.nisfeb.talon.notify

import io.nisfeb.talon.ui.JvmUiSettingsStore
import io.nisfeb.talon.ui.StoredRelaySettings
import io.nisfeb.talon.util.AppDirs
import java.io.File

/**
 * JSON-file-backed relay state for desktop. Sits next to the other
 * per-process settings files. Atomic-move write so a JVM crash
 * mid-write can't truncate the file to junk.
 */
class DesktopRelaySettings(
    file: File = File(AppDirs.userData, "relay.json"),
) : RelaySettings by StoredRelaySettings(JvmUiSettingsStore(file))
