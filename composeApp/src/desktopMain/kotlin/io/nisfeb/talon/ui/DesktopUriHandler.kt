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
        tryOpenUri(uri)
    }

    /**
     * Same as [openUri] but reports whether an opener was actually
     * launched, for callers with their own fallback (the update
     * installer surfaces a failure message instead of silently doing
     * nothing). [openUri] swallows the result because the UriHandler
     * interface has no error channel and most click handlers don't
     * have one either.
     */
    fun tryOpenUri(uri: String): Boolean {
        when (val target = openTarget(uri, java.io.File(io.nisfeb.talon.util.openedFilesDir()))) {
            null -> {
                // A message's link reaches here as the sender wrote it, and
                // the system opener runs anything it has a handler for: a
                // file, a .desktop launcher, an smb share, a custom scheme.
                Log.w(TAG, "not opening a link of this kind: ${uri.substringBefore(':').take(20)}")
                return false
            }
            is java.io.File -> return openFile(target)
        }
        val cmd = when {
            "linux" in osName -> arrayOf("xdg-open", uri)
            "mac" in osName || "darwin" in osName -> arrayOf("open", uri)
            "windows" in osName -> arrayOf("rundll32", "url.dll,FileProtocolHandler", uri)
            else -> null
        }
        if (cmd != null && tryRun(cmd)) return true

        // Last-resort AWT fallback for an OS we don't recognise.
        return runCatching {
            val desktop = java.awt.Desktop.getDesktop()
            if (java.awt.Desktop.isDesktopSupported() &&
                desktop.isSupported(java.awt.Desktop.Action.BROWSE)
            ) {
                desktop.browse(java.net.URI(uri))
                true
            } else {
                Log.w(TAG, "no native opener for $osName and AWT BROWSE unsupported")
                false
            }
        }.getOrElse {
            Log.w(TAG, "AWT Desktop.browse failed for $uri", it)
            false
        }
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

    /**
     * Hand a local file to the system opener by PATH (a deb into the package
     * installer, a dmg to mount, an msi to run) and report whether it was
     * actually accepted. Unlike [openUri] this passes the plain path, not a
     * `file:` URI (java File.toURI yields a single-slash `file:/…` form some
     * openers mishandle), and it CHECKS the opener's exit code instead of
     * assuming a launched process succeeded — so a missing handler (no GUI
     * package installer, a `.dmg` with no mounter) is visible to the caller
     * rather than looking like a silent success.
     */
    fun openFile(file: java.io.File): Boolean {
        val path = file.absolutePath
        val cmd = when {
            "linux" in osName -> arrayOf("xdg-open", path)
            "mac" in osName || "darwin" in osName -> arrayOf("open", path)
            "windows" in osName -> arrayOf("rundll32", "url.dll,FileProtocolHandler", path)
            else -> return runCatching { java.awt.Desktop.getDesktop().open(file); true }.getOrDefault(false)
        }
        return runCatching {
            val p = ProcessBuilder(*cmd)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            // open / xdg-open / rundll32 all hand the file off and exit
            // promptly. Still running after the grace period means it handed
            // off to a foreground app — treat that as success; a prompt exit
            // is judged by its code.
            if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) true
            else p.exitValue() == 0
        }.getOrElse {
            Log.w(TAG, "file opener ${cmd.firstOrNull()} failed", it)
            false
        }
    }
}

/** The link kinds the system opener is given. Talon's own (talon:, urb:) are routed before this. */
private val OPENABLE_SCHEMES = setOf("http", "https", "mailto")

/**
 * What [uri] may be opened as: itself (a web or mail link), a file Talon
 * made in [opened] (an event's .ics), or null for anything else.
 */
internal fun openTarget(uri: String, opened: java.io.File): Any? {
    val scheme = uri.substringBefore(':', "").lowercase()
    if (scheme in OPENABLE_SCHEMES) return uri
    if (scheme != "file") return null
    val file = runCatching { java.io.File(java.net.URI(uri)).canonicalFile }.getOrNull() ?: return null
    return file.takeIf { it.parentFile == opened.canonicalFile }
}
