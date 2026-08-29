package io.nisfeb.talon.util

/** iOS logging goes to stdout, which Xcode/Console surfaces per-process.
 *  println avoids NSLog's C-variadic interop awkwardness. */
actual object Log {
    actual fun i(tag: String, msg: String) {
        println("INFO  [$tag] $msg")
    }

    actual fun w(tag: String, msg: String, t: Throwable?) {
        println("WARN  [$tag] $msg")
        t?.let { println(it.stackTraceToString()) }
    }

    actual fun e(tag: String, msg: String, t: Throwable?) {
        println("ERROR [$tag] $msg")
        t?.let { println(it.stackTraceToString()) }
    }
}
