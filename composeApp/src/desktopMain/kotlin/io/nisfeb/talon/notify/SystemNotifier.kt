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
 * - macOS: shells out to `osascript`. Real Notification Center toasts.
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
) : Notifier {

    private enum class Backend { LINUX_NOTIFY_SEND, LINUX_GDBUS, MACOS_OSASCRIPT, FALLBACK }

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
                Backend.MACOS_OSASCRIPT -> notifyOsaScript(title, body)
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
            "mac" in osName -> Backend.MACOS_OSASCRIPT
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
        args += title
        args += body
        spawn(args)
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

    private fun notifyOsaScript(title: String, body: String) {
        val script = buildString {
            append("display notification \"")
            append(escapeAppleScript(body))
            append("\" with title \"")
            append(escapeAppleScript(title))
            append("\"")
        }
        spawn(listOf("osascript", "-e", script))
    }

    private fun escapeAppleScript(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

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
