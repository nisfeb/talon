package io.nisfeb.talon.call

import io.nisfeb.talon.util.Log

/**
 * GLib's default main loop, on a thread of its own. A Wayland screen
 * share goes through the xdg-desktop-portal, and libwebrtc hears the
 * portal's D-Bus replies as GLib callbacks. A JVM runs no GLib loop, so
 * nothing ran them: the portal never opened its dialog, and the share
 * started, sent nothing, and said nothing.
 */
internal object GLibLoop {
    private interface GLib : com.sun.jna.Library {
        fun g_main_context_iteration(context: com.sun.jna.Pointer?, mayBlock: Boolean): Boolean
    }

    private var started = false

    @Synchronized
    fun ensureRunning() {
        if (started) return
        started = true
        val glib = runCatching { com.sun.jna.Native.load("glib-2.0", GLib::class.java) }
            .onFailure { Log.w("ScreenShare", "no GLib here: the system's share dialog cannot open", it) }
            .getOrNull() ?: return
        kotlin.concurrent.thread(isDaemon = true, name = "glib-main") {
            // Blocks until there is something to run. False is another
            // thread owning the loop, or nothing run: wait, do not spin.
            while (true) if (!glib.g_main_context_iteration(null, true)) Thread.sleep(20)
        }
    }
}

/** A Wayland session: the portal picks what is shared, and libwebrtc lists only placeholders. */
internal val isWayland: Boolean
    get() = !System.getenv("WAYLAND_DISPLAY").isNullOrEmpty() || System.getenv("XDG_SESSION_TYPE") == "wayland"
