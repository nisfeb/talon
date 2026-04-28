package io.nisfeb.talon.util

/**
 * Platform-agnostic logging facade. Use this from commonMain instead of
 * `android.util.Log` (which doesn't exist on desktop) or `println`
 * (which is unstructured and unfilterable).
 *
 * On Android, delegates to `android.util.Log` so existing logcat-based
 * debugging workflows still work. On desktop, writes to System.err with
 * a "LEVEL [tag] msg" prefix.
 */
expect object Log {
    fun i(tag: String, msg: String)
    fun w(tag: String, msg: String, t: Throwable? = null)
    fun e(tag: String, msg: String, t: Throwable? = null)
}
