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
 * The file is `<userData>/log/talon.log`, rotated once past 2 MB to a
 * single `.1` backup so it can't grow unbounded. Each line is
 * wall-clock stamped (HH:mm:ss.SSS) so latency between events — e.g. a
 * post send and its echo/reap — can be read straight off the log.
 */
actual object Log {
    private val fileOut: PrintStream? by lazy {
        runCatching {
            val dir = File(AppDirs.userData, "log").apply { mkdirs() }
            val f = File(dir, "talon.log")
            if (f.exists() && f.length() > 2_000_000L) {
                f.copyTo(File(dir, "talon.log.1"), overwrite = true)
                f.writeText("")
            }
            PrintStream(FileOutputStream(f, true), true, "UTF-8")
        }.getOrNull()
    }

    private fun emit(line: String, t: Throwable?) {
        val stamped = "${LocalTime.now()} $line"
        System.err.println(stamped)
        t?.printStackTrace(System.err)
        fileOut?.let { out ->
            out.println(stamped)
            t?.printStackTrace(out)
        }
    }

    actual fun i(tag: String, msg: String) = emit("INFO  [$tag] $msg", null)
    actual fun w(tag: String, msg: String, t: Throwable?) = emit("WARN  [$tag] $msg", t)
    actual fun e(tag: String, msg: String, t: Throwable?) = emit("ERROR [$tag] $msg", t)
}
