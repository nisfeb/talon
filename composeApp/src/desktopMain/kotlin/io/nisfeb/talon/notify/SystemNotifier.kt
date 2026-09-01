package io.nisfeb.talon.notify

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference

/**
 * Desktop [Notifier] that emits real OS notifications instead of
 * Compose's AWT-balloon fallback.
 *
 * - Linux: shells out to `notify-send` if present, else falls back to
 *   `gdbus` (always available — glib is a hard dep of GTK and KDE).
 *   Either path goes through libnotify / org.freedesktop.Notifications,
 *   so the user's notification daemon (GNOME Shell, dunst, mako, KDE
 *   Plasma) renders it natively — appears in notification history,
 *   honors theme, respects Do Not Disturb.
 * - macOS: delegates to [trayFallback], which routes via AWT TrayIcon
 *   → NSUserNotificationCenter. This attributes the notification to
 *   the running .app bundle (Talon.app when launched from the DMG), so
 *   the Talon icon appears next to the message. The previous
 *   `osascript display notification` path was attributed to Script
 *   Editor / osascript and showed the wrong icon — Apple tightened
 *   that path years ago and it can't be customized without bundling
 *   a third-party helper (terminal-notifier).
 * - Windows: delegates straight to [trayFallback]. Java 9+ wraps
 *   [java.awt.TrayIcon.displayMessage] onto native ITaskbarList3
 *   toasts on Windows 10/11, so the AWT path is already correct there.
 *
 * The `trayFallback` closure is also used as the final fallback on
 * any platform if the native path errors (binary missing on a
 * stripped Linux install, sandbox blocks subprocess, etc.) — once
 * a backend fails, it's demoted permanently for this process so the
 * fork/exec cost isn't paid on every notification.
 *
 * notify(...) returns immediately. The actual fork/exec runs on a
 * daemon thread so callers (UI tick, ingest loop) never block.
 */
class SystemNotifier(
    private val trayFallback: (String, String) -> Unit,
    // Invoked when the user clicks the notification (Linux
    // notify-send path only — Main.kt passes its bring-to-front
    // routine). Other backends have no click signal to hook.
    private val onActivate: () -> Unit = {},
) : Notifier {

    private enum class Backend { LINUX_NOTIFY_SEND, LINUX_GDBUS, FALLBACK }

    @Volatile
    private var backend: Backend = pickInitialBackend()

    private val iconFile = AtomicReference<File?>(null)

    override fun notify(title: String, body: String) {
        Thread {
            // Try up to one demotion (notify-send → gdbus on Linux)
            // before serving via the tray fallback. The retry is what
            // keeps the *first* notification on a notify-send-less host
            // from being an ugly Swing balloon.
            repeat(2) {
                if (tryEmit(title, body)) return@Thread
            }
            runCatching { trayFallback(title, body) }
        }.apply {
            isDaemon = true
            name = "Talon-notify"
        }.start()
    }

    private fun tryEmit(title: String, body: String): Boolean {
        return try {
            when (backend) {
                Backend.LINUX_NOTIFY_SEND -> notifyNotifySend(title, body)
                Backend.LINUX_GDBUS -> notifyGdbus(title, body)
                Backend.FALLBACK -> return false
            }
            true
        } catch (_: IOException) {
            // Binary missing on PATH. Demote permanently for this
            // process and let the caller retry with the new backend.
            demote()
            false
        } catch (_: Throwable) {
            false
        }
    }

    private fun pickInitialBackend(): Backend {
        val osName = System.getProperty("os.name").lowercase()
        return when {
            "linux" in osName -> Backend.LINUX_NOTIFY_SEND
            // macOS and Windows go straight to trayFallback. The AWT
            // TrayIcon route on macOS attributes notifications to
            // Talon.app (icon + name correct); Windows TrayIcon maps
            // to ITaskbarList3 toasts natively.
            else -> Backend.FALLBACK
        }
    }

    private fun demote() {
        backend = when (backend) {
            // notify-send missing → try gdbus before giving up
            Backend.LINUX_NOTIFY_SEND -> Backend.LINUX_GDBUS
            else -> Backend.FALLBACK
        }
    }

    private fun notifyNotifySend(title: String, body: String) {
        val args = mutableListOf("notify-send", "-a", "Talon")
        ensureIconFile()?.let {
            args += "-i"
            args += it.absolutePath
        }
        // The "default" action fires when the user clicks the
        // notification body itself. With -A, notify-send stays alive
        // until the toast closes and prints the activated action's
        // name on stdout — we're already on the per-notification
        // daemon thread, so blocking on that here is free.
        args += "-A"
        args += "default=Open"
        // -A implies --wait, and the wait is only bounded when an
        // expire-time is set: GNOME parks non-transient toasts in the
        // tray without ever closing them, and each unbounded wait is a
        // parked thread plus a live notify-send. Ten seconds trades
        // clicks on tray-archived toasts for a bounded process count.
        args += "-t"
        args += "10000"
        args += title
        args += body
        val process = ProcessBuilder(args)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val action = process.inputStream.bufferedReader().use { it.readLine() }
        if (process.waitFor() != 0) {
            // Likely a notify-send too old for -A (< 0.7.10). Throwing
            // IOException routes through tryEmit's demotion so the
            // retry lands on gdbus instead of silently dropping toasts.
            throw IOException("notify-send exited ${process.exitValue()}")
        }
        if (action?.trim() == "default") onActivate()
    }

    private fun notifyGdbus(title: String, body: String) {
        // Direct call to org.freedesktop.Notifications.Notify. Args:
        //   app_name, replaces_id, app_icon, summary, body,
        //   actions[], hints{}, expire_timeout_ms
        val iconPath = ensureIconFile()?.absolutePath ?: ""
        spawn(
            listOf(
                "gdbus", "call", "--session",
                "--dest", "org.freedesktop.Notifications",
                "--object-path", "/org/freedesktop/Notifications",
                "--method", "org.freedesktop.Notifications.Notify",
                "Talon",
                "0",
                iconPath,
                title,
                body,
                "[]",
                "{}",
                "-1",
            )
        )
    }

    private fun spawn(args: List<String>) {
        ProcessBuilder(args)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
    }

    private fun ensureIconFile(): File? {
        iconFile.get()?.let { return it }
        val bytes = ClassLoader.getSystemResourceAsStream("icon.png")?.use { it.readBytes() }
            ?: return null
        return runCatching {
            // Per-PID temp file so concurrent Talon instances don't
            // race on the same path.
            val tmp = File(
                System.getProperty("java.io.tmpdir"),
                "talon-notify-${ProcessHandle.current().pid()}.png",
            )
            if (!tmp.exists()) {
                Files.write(tmp.toPath(), bytes)
                tmp.deleteOnExit()
            }
            iconFile.compareAndSet(null, tmp)
            iconFile.get()
        }.getOrNull()
    }
}
