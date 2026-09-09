package io.nisfeb.talon.util

import java.awt.EventQueue

/**
 * Notices when the UI thread stops responding and writes its stack to
 * the log, so a freeze becomes a diagnosis instead of a report. A
 * daemon thread posts a heartbeat to the AWT event queue every second;
 * when one is not serviced within [stallMs], the event thread's stack
 * is logged once, and again if the stall is still going a while later.
 * Costs one trivial event per second.
 */
object UiWatchdog {
    @Volatile private var lastBeat = System.nanoTime()

    fun start(stallMs: Long = 4_000) {
        Thread({
            var reportedAt = 0L
            while (true) {
                try {
                    Thread.sleep(1_000)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                EventQueue.invokeLater { lastBeat = System.nanoTime() }
                val stalledMs = (System.nanoTime() - lastBeat) / 1_000_000
                if (stalledMs >= stallMs && System.nanoTime() - reportedAt > 15_000_000_000L) {
                    reportedAt = System.nanoTime()
                    val edt = Thread.getAllStackTraces().entries
                        .firstOrNull { it.key.name.startsWith("AWT-EventQueue") }
                    val where = edt?.value?.take(25)?.joinToString("\n") { "    at $it" } ?: "    (event thread not found)"
                    Log.w(TAG, "UI thread unresponsive for ${stalledMs}ms; event thread is at:\n$where")
                }
            }
        }, "Talon-ui-watchdog").apply { isDaemon = true }.start()
    }

    private const val TAG = "UiWatchdog"
}
