package io.nisfeb.talon.util

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.time.Clock

/**
 * The app's recent log lines, kept in memory for a bug report. Every
 * platform's [Log] feeds it: Android writes to logcat and iOS to stdout,
 * neither of which Talon can read back, and desktop's file holds other
 * runs too. It holds this run only, so a report sent after a crash and a
 * restart starts after the crash.
 */
object LogBuffer {
    /** About the last half hour of a busy session, and at most ~500 KB. */
    const val MAX_LINES = 500

    /** A line longer than this is cut: one JSON dump must not push out the rest. */
    const val MAX_LINE = 1_000

    private val lock = SynchronizedObject()
    private val lines = ArrayDeque<String>()

    fun add(level: String, tag: String, msg: String, t: Throwable? = null) {
        val cause = t?.let { " (${it::class.simpleName}: ${it.message})" }.orEmpty()
        val line = "${Clock.System.now()} $level [$tag] $msg$cause".let { if (it.length > MAX_LINE) it.take(MAX_LINE) + " …" else it }
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
    }

    /** The lines kept, oldest first. */
    fun recent(): List<String> = synchronized(lock) { lines.toList() }

    internal fun clearForTest() = synchronized(lock) { lines.clear() }
}
