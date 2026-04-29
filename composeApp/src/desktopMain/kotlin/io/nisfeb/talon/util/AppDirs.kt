package io.nisfeb.talon.util

import java.io.File
import java.util.Locale

/**
 * Per-OS user-data directory for Talon. Returns the platform-
 * idiomatic location:
 *   - Windows → %LOCALAPPDATA%\Talon (falling back to %APPDATA%\Talon
 *     and finally to ~\.talon if neither env var is set)
 *   - macOS   → ~/Library/Application Support/Talon
 *   - Linux/other → $XDG_CONFIG_HOME/talon, falling back to
 *     ~/.config/talon
 *
 * The directory is created on first call. Used by DesktopSessionStore,
 * DesktopAiSettings, and AppDatabase.desktop so all desktop user data
 * lives under one root the user can find via their platform's
 * standard "user data" path.
 */
object AppDirs {
    val userData: File by lazy { resolve().also { it.mkdirs() } }

    private fun resolve(): File {
        val osName = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
        val userHome = System.getProperty("user.home")
            ?: error("user.home system property not set")
        return when {
            osName.contains("win") -> {
                val localAppData = System.getenv("LOCALAPPDATA")
                    ?: System.getenv("APPDATA")
                if (localAppData != null) File(localAppData, "Talon")
                else File(userHome, ".talon")
            }
            osName.contains("mac") || osName.contains("darwin") ->
                File(userHome, "Library/Application Support/Talon")
            else -> {
                val xdg = System.getenv("XDG_CONFIG_HOME")
                if (!xdg.isNullOrBlank()) File(xdg, "talon")
                else File(userHome, ".config/talon")
            }
        }
    }
}
