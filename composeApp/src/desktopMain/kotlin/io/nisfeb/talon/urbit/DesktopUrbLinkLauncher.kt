package io.nisfeb.talon.urbit

import io.nisfeb.talon.util.Log

/**
 * Desktop [UrbLinkLauncher]. Detects a registered urb:// scheme
 * handler and opens via the platform opener.
 *
 *  - Linux: `xdg-mime query default x-scheme-handler/urb` returns the
 *    handler .desktop file (empty when none) — a reliable installed
 *    check. Open with `xdg-open`, the same path
 *    [io.nisfeb.talon.ui.DesktopUriHandler] uses (works under X11 and
 *    Wayland alike).
 *  - macOS / Windows: scheme-handler detection is unreliable without
 *    deeper OS APIs, so we attempt the open best-effort and report
 *    [UrbLaunchResult.Opened]. If nothing is registered the OS shows
 *    its own "no app" affordance — acceptable on these smaller
 *    desktop cohorts, and the link is always copyable.
 */
object DesktopUrbLinkLauncher : UrbLinkLauncher {
    private const val TAG = "UrbLinkLauncher"
    private val osName = System.getProperty("os.name", "").lowercase()

    override fun open(url: String): UrbLaunchResult = when {
        "linux" in osName -> openLinux(url)
        "mac" in osName || "darwin" in osName -> openBestEffort(url, arrayOf("open", url))
        "windows" in osName ->
            openBestEffort(url, arrayOf("rundll32", "url.dll,FileProtocolHandler", url))
        else -> openBestEffort(url, null)
    }

    private fun openLinux(url: String): UrbLaunchResult {
        if (!hasLinuxHandler()) return UrbLaunchResult.NotInstalled
        return if (run(arrayOf("xdg-open", url))) UrbLaunchResult.Opened
        else UrbLaunchResult.Failed
    }

    private fun hasLinuxHandler(): Boolean = runCatching {
        val proc = ProcessBuilder("xdg-mime", "query", "default", "x-scheme-handler/urb")
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        out.isNotEmpty()
    }.getOrElse {
        Log.w(TAG, "xdg-mime query failed: ${it.message}")
        // If we can't even run xdg-mime, fall back to "attempt anyway"
        // rather than falsely reporting NotInstalled.
        true
    }

    private fun openBestEffort(url: String, cmd: Array<String>?): UrbLaunchResult {
        if (cmd != null && run(cmd)) return UrbLaunchResult.Opened
        // Last-resort AWT browse for an unrecognised OS.
        return runCatching {
            val desktop = java.awt.Desktop.getDesktop()
            if (java.awt.Desktop.isDesktopSupported() &&
                desktop.isSupported(java.awt.Desktop.Action.BROWSE)
            ) {
                desktop.browse(java.net.URI(url))
                UrbLaunchResult.Opened
            } else {
                UrbLaunchResult.Failed
            }
        }.getOrElse {
            Log.w(TAG, "best-effort open failed for $url: ${it.message}")
            UrbLaunchResult.Failed
        }
    }

    private fun run(cmd: Array<String>): Boolean = runCatching {
        ProcessBuilder(*cmd)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
        true
    }.getOrElse {
        Log.w(TAG, "opener ${cmd.firstOrNull()} failed: ${it.message}")
        false
    }
}
