package io.nisfeb.talon.util

actual object Log {
    actual fun i(tag: String, msg: String) {
        LogBuffer.add("INFO ", tag, msg)
        android.util.Log.i(tag, msg)
    }
    actual fun w(tag: String, msg: String, t: Throwable?) {
        LogBuffer.add("WARN ", tag, msg, t)
        android.util.Log.w(tag, msg, t)
    }
    actual fun e(tag: String, msg: String, t: Throwable?) {
        LogBuffer.add("ERROR", tag, msg, t)
        android.util.Log.e(tag, msg, t)
    }
}
