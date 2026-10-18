package io.nisfeb.talon.notify

import io.nisfeb.talon.util.cacheDirPath
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
    // routine). Other backends have no click signal to hook. The
    // notification's key then opens its place (openNotificationKey).
    private val onActivate: () -> Unit = {},
    linux: Boolean = "linux" in System.getProperty("os.name").lowercase(),
    // Starts a backend's process; a test hands in a fake one.
    private val start: (List<String>) -> Process = {
        ProcessBuilder(it).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    },
) : Notifier {

    private enum class Backend { LINUX_NOTIFY_SEND, LINUX_GDBUS, FALLBACK }

    @Volatile
    private var backend: Backend = pickInitialBackend(linux)

    private val iconFile = AtomicReference<File?>(null)

    // ponytail: ids per key for the life of the process, the last 20 each; a toast parked in GNOME's tray stays closable.
    private val shown = HashMap<String, ArrayDeque<Long>>()

    private fun record(key: String?, id: Long?) {
        if (key == null || id == null) return
        synchronized(shown) {
            val q = shown.getOrPut(key) { ArrayDeque() }
            q.addLast(id)
            while (q.size > 20) q.removeFirst()
        }
    }

    /** Closes what was shown under [key]. Linux only: the macOS and Windows tray balloons cannot be withdrawn. */
    override fun clear(key: String) {
        val ids = synchronized(shown) { shown.remove(key) } ?: return
        if (backend == Backend.FALLBACK) return
        Thread {
            ids.forEach { id ->
                runCatching {
                    spawn(
                        listOf(
                            "gdbus", "call", "--session",
                            "--dest", "org.freedesktop.Notifications",
                            "--object-path", "/org/freedesktop/Notifications",
                            "--method", "org.freedesktop.Notifications.CloseNotification",
                            id.toString(),
                        )
                    )
                }
            }
        }.apply {
            isDaemon = true
            name = "Talon-notify-clear"
        }.start()
    }

    override fun notify(title: String, body: String, key: String?) {
        Thread { deliver(title, body, key) }.apply {
            isDaemon = true
            name = "Talon-notify"
        }.start()
    }

    /** Shows one notification, on the calling thread. */
    internal fun deliver(title: String, body: String, key: String?) {
        // Try up to one demotion (notify-send → gdbus on Linux)
        // before serving via the tray fallback. The retry is what
        // keeps the *first* notification on a notify-send-less host
        // from being an ugly Swing balloon.
        repeat(2) {
            if (tryEmit(title, body, key)) return
        }
        runCatching { trayFallback(title, body) }
    }

    private fun tryEmit(title: String, body: String, key: String?): Boolean {
        return try {
            when (backend) {
                Backend.LINUX_NOTIFY_SEND -> notifyNotifySend(title, body, key)
                Backend.LINUX_GDBUS -> notifyGdbus(title, body, key)
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

    private fun pickInitialBackend(linux: Boolean): Backend {
        return when {
            linux -> Backend.LINUX_NOTIFY_SEND
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

    private fun notifyNotifySend(title: String, body: String, key: String?) {
        val process = start(notifySendArgs(ensureIconFile()?.absolutePath, title, body))
        val action = process.inputStream.bufferedReader().use { out ->
            record(key, out.readLine()?.trim()?.toLongOrNull())
            out.readLine()
        }
        if (process.waitFor() != 0) {
            // Likely a notify-send too old for -A (< 0.7.10). Throwing
            // IOException routes through tryEmit's demotion so the
            // retry lands on gdbus instead of silently dropping toasts.
            throw IOException("notify-send exited ${process.exitValue()}")
        }
        if (action?.trim() == "default") {
            onActivate()
            key?.let(::openNotificationKey)
        }
    }

    private fun notifyGdbus(title: String, body: String, key: String?) {
        // The answer is "(uint32 <id>,)": kept so the toast can be closed later.
        val process = start(gdbusNotifyArgs(ensureIconFile()?.absolutePath ?: "", title, body))
        val out = process.inputStream.bufferedReader().use { it.readText() }
        // A refused call (a bad argument, no notification daemon) used
        // to count as shown, and every later toast went nowhere. The
        // IOException demotes to the tray, as for notify-send.
        if (process.waitFor() != 0) throw IOException("gdbus exited ${process.exitValue()}")
        record(key, gdbusId(out))
    }

    private fun spawn(args: List<String>) {
        start(args).inputStream.close()
    }

    private fun ensureIconFile(): File? {
        iconFile.get()?.let { return it }
        val bytes = ClassLoader.getSystemResourceAsStream("icon.png")?.use { it.readBytes() }
            ?: return null
        return runCatching {
            // Under Talon's own cache, not the shared temp directory,
            // where a predictable name could be someone else's symlink.
            val icon = File(cacheDirPath, "notify-icon.png")
            if (icon.length() != bytes.size.toLong()) {
                icon.parentFile.mkdirs()
                Files.write(icon.toPath(), bytes)
            }
            iconFile.compareAndSet(null, icon)
            iconFile.get()
        }.getOrNull()
    }
}

/**
 * notify-send's arguments. The "default" action fires when the user
 * clicks the notification body itself. With -A, notify-send stays alive
 * until the toast closes and prints the activated action's name on
 * stdout; the caller is already on the per-notification daemon thread,
 * so blocking on that is free. -p prints the id first, before the wait,
 * so the toast can be closed later.
 *
 * -A implies --wait, and the wait is only bounded when an expire-time is
 * set: GNOME parks non-transient toasts in the tray without ever closing
 * them, and each unbounded wait is a parked thread plus a live
 * notify-send. Ten seconds trades clicks on tray-archived toasts for a
 * bounded process count.
 *
 * "--" ends the options, so a title or body that starts with "-" is
 * shown, not read as an option.
 */
internal fun notifySendArgs(iconPath: String?, title: String, body: String): List<String> = buildList {
    add("notify-send"); add("-a"); add("Talon")
    iconPath?.let { add("-i"); add(it) }
    add("-p"); add("-A"); add("default=Open")
    add("-t"); add("10000")
    add("--"); add(title); add(body)
}

/**
 * gdbus's arguments for org.freedesktop.Notifications.Notify: app_name,
 * replaces_id, app_icon, summary, body, actions[], hints{},
 * expire_timeout_ms. Without "--" gdbus reads the -1 timeout (or a body
 * starting with "-") as an option, prints its usage and exits 1.
 */
internal fun gdbusNotifyArgs(iconPath: String, title: String, body: String): List<String> = listOf(
    "gdbus", "call", "--session",
    "--dest", "org.freedesktop.Notifications",
    "--object-path", "/org/freedesktop/Notifications",
    "--method", "org.freedesktop.Notifications.Notify",
    "--",
    "Talon", "0", iconPath, title, body, "[]", "{}", "-1",
)

/** The id in gdbus's answer to Notify, "(uint32 42,)", or null. */
internal fun gdbusId(out: String): Long? = Regex("""uint32 (\d+)""").find(out)?.groupValues?.get(1)?.toLongOrNull()
