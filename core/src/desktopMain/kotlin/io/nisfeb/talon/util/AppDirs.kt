package io.nisfeb.talon.util

import java.io.File
import java.util.Locale

private const val TAG = "AppDirs"

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
    /**
     * An optional profile name from `TALON_PROFILE`, so a second build
     * (a beta AppImage, a test copy) keeps its own data directory,
     * sessions, database and single-instance lock beside the everyday
     * one. Null is the everyday install. Letters, digits, `-`, `_`.
     */
    val profile: String? = System.getenv("TALON_PROFILE")
        ?.trim()?.takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' } }

    private val lowerName: String get() = profile?.let { "talon-$it" } ?: "talon"
    private val titleName: String get() = profile?.let { "Talon-$it" } ?: "Talon"

    val userData: File by lazy {
        resolve().also { dir ->
            // mkdirs() returns false on permission denied, read-only
            // home, or a deleted profile dir on Windows. Surface the
            // root cause instead of letting downstream callers fail
            // with an opaque "unable to open database" later.
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "Failed to create user-data dir: ${dir.absolutePath}")
            }
        }
    }

    private fun resolve(): File {
        val osName = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
        val userHome = System.getProperty("user.home")
            ?: error("user.home system property not set")
        return when {
            osName.contains("win") -> {
                val localAppData = System.getenv("LOCALAPPDATA")
                    ?: System.getenv("APPDATA")
                if (localAppData != null) File(localAppData, titleName)
                else File(userHome, ".$lowerName")
            }
            osName.contains("mac") || osName.contains("darwin") ->
                File(userHome, "Library/Application Support/$titleName")
            else -> {
                val xdg = System.getenv("XDG_CONFIG_HOME")
                if (!xdg.isNullOrBlank()) File(xdg, lowerName)
                else File(userHome, ".config/$lowerName")
            }
        }
    }
}
