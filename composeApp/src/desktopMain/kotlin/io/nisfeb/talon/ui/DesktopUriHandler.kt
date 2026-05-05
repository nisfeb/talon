package io.nisfeb.talon.ui

import androidx.compose.ui.platform.UriHandler
import io.nisfeb.talon.util.Log

/**
 * Open URLs from inside Compose without crashing on Wayland-only Linux.
 *
 * Compose's default desktop UriHandler delegates to
 * `java.awt.Desktop.browse(URI)`, which opens with the OS browser via
 * the AWT toolkit's xdg integration. That works on X11 + GNOME / KDE
 * but throws on Wayland-only setups (Hyprland, Sway, Omarchy, …)
 * because AWT's Desktop API was never updated for Wayland. The
 * exception propagates out of Compose's click handler and can take
 * down the window.
 *
 * Skip AWT entirely on Linux and shell out to `xdg-open`, which works
 * uniformly under both X11 and Wayland. macOS and Windows still go
 * through AWT-equivalent command-line tools (`open`, `rundll32`)
 * — same reasoning, kept minimal so a missing AWT class on a future
 * leaner runtime can't break URL clicks.
 */
object DesktopUriHandler : UriHandler {

    private const val TAG = "DesktopUriHandler"
    private val osName = System.getProperty("os.name", "").lowercase()

    override fun openUri(uri: String) {
        val cmd = when {
            "linux" in osName -> arrayOf("xdg-open", uri)
            "mac" in osName || "darwin" in osName -> arrayOf("open", uri)
            "windows" in osName -> arrayOf("rundll32", "url.dll,FileProtocolHandler", uri)
            else -> null
        }
        if (cmd != null && tryRun(cmd)) return

        // Last-resort AWT fallback for an OS we don't recognise.
        runCatching {
            val desktop = java.awt.Desktop.getDesktop()
            if (java.awt.Desktop.isDesktopSupported() &&
                desktop.isSupported(java.awt.Desktop.Action.BROWSE)
            ) {
                desktop.browse(java.net.URI(uri))
            } else {
                Log.w(TAG, "no native opener for $osName and AWT BROWSE unsupported")
            }
        }.onFailure { Log.w(TAG, "AWT Desktop.browse failed for $uri", it) }
    }

    private fun tryRun(cmd: Array<String>): Boolean = runCatching {
        ProcessBuilder(*cmd)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
        true
    }.getOrElse {
        Log.w(TAG, "native opener ${cmd.firstOrNull()} failed", it)
        false
    }
}
