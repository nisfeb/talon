package io.nisfeb.talon.util

/** iOS logging goes to stdout, which Xcode/Console surfaces per-process.
 *  println avoids NSLog's C-variadic interop awkwardness. */
actual object Log {
    actual fun i(tag: String, msg: String) {
        LogBuffer.add("INFO ", tag, msg)
        println("INFO  [$tag] $msg")
    }

    actual fun w(tag: String, msg: String, t: Throwable?) {
        LogBuffer.add("WARN ", tag, msg, t)
        println("WARN  [$tag] $msg")
        t?.let { println(it.stackTraceToString()) }
    }

    actual fun e(tag: String, msg: String, t: Throwable?) {
        LogBuffer.add("ERROR", tag, msg, t)
        println("ERROR [$tag] $msg")
        t?.let { println(it.stackTraceToString()) }
    }
}
