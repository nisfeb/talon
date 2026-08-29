package io.nisfeb.talon.util

actual object Log {
    actual fun i(tag: String, msg: String) {
        android.util.Log.i(tag, msg)
    }
    actual fun w(tag: String, msg: String, t: Throwable?) {
        android.util.Log.w(tag, msg, t)
    }
    actual fun e(tag: String, msg: String, t: Throwable?) {
        android.util.Log.e(tag, msg, t)
    }
}
