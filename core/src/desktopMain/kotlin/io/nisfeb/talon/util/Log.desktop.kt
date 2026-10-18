package io.nisfeb.talon.util

import java.io.File
import java.io.FileOutputStream
import java.io.PrintStream
import java.time.LocalTime

/**
 * Logs to stderr AND to a file under the user-data dir, because a
 * packaged app (AppImage / .dmg / .msi) detaches from the launching
 * shell — stderr goes to /dev/null, so without the file sink a user's
 * bug report ("sending is slow", a crash) leaves no trace to read.
 * The file is `<userData>/log/talon.log`, rotated past 2 MB to a
 * single `.1` backup so it can't grow unbounded ([RotatingLog]). Each line is
 * wall-clock stamped (HH:mm:ss.SSS) so latency between events — e.g. a
 * post send and its echo/reap — can be read straight off the log.
 */
actual object Log {
    private val file: RotatingLog by lazy {
        RotatingLog(File(File(AppDirs.userData, "log").apply { mkdirs() }, "talon.log"))
    }

    private fun emit(line: String, t: Throwable?) {
        val stamped = "${LocalTime.now()} $line"
        System.err.println(stamped)
        t?.printStackTrace(System.err)
        file.write(stamped, t)
    }

    actual fun i(tag: String, msg: String) { LogBuffer.add("INFO ", tag, msg); emit("INFO  [$tag] $msg", null) }
    actual fun w(tag: String, msg: String, t: Throwable?) { LogBuffer.add("WARN ", tag, msg, t); emit("WARN  [$tag] $msg", t) }
    actual fun e(tag: String, msg: String, t: Throwable?) { LogBuffer.add("ERROR", tag, msg, t); emit("ERROR [$tag] $msg", t) }
}

/**
 * A log file kept under [limit] bytes plus one `.1` backup. The size is
 * checked at the first line and every [every] lines after: checked only
 * at launch, a Talon left running for weeks grew it without bound.
 */
internal class RotatingLog(
    private val file: File,
    private val limit: Long = 2_000_000L,
    private val every: Int = 500,
) {
    private var out: PrintStream? = null
    private var opened = false
    private var lines = 0

    @Synchronized
    fun write(line: String, t: Throwable?) {
        if (!opened || (lines % every == 0 && file.length() > limit)) reopen()
        lines++
        out?.let { o ->
            o.println(line)
            t?.printStackTrace(o)
        }
    }

    private fun reopen() {
        opened = true
        runCatching { out?.close() }
        out = runCatching {
            if (file.length() > limit) {
                file.copyTo(File(file.parentFile, file.name + ".1"), overwrite = true)
                file.writeText("")
            }
            PrintStream(FileOutputStream(file, true), true, "UTF-8")
        }.getOrNull()
    }
}
