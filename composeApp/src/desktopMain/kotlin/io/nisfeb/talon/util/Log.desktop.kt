package io.nisfeb.talon.util

actual object Log {
    actual fun i(tag: String, msg: String) {
        System.err.println("INFO  [$tag] $msg")
    }
    actual fun w(tag: String, msg: String, t: Throwable?) {
        System.err.println("WARN  [$tag] $msg")
        t?.printStackTrace(System.err)
    }
    actual fun e(tag: String, msg: String, t: Throwable?) {
        System.err.println("ERROR [$tag] $msg")
        t?.printStackTrace(System.err)
    }
}
