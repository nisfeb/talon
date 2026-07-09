package io.nisfeb.talon.ai

import android.content.Context
import android.os.PowerManager

/**
 * A partial wake lock for a headless alarm fire, held while the receiver's
 * goAsync() work runs. Non-reference-counted with a hard timeout, so a run
 * that dies mid-flight can't pin the CPU awake.
 *
 * Android-only: no desktop analog — the desktop paths only run while the
 * window is open (CLAUDE.md §6).
 */
internal fun Context.acquireTalonWakeLock(tag: String, timeoutMs: Long): PowerManager.WakeLock {
    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "talon:$tag").apply {
        setReferenceCounted(false)
        acquire(timeoutMs)
    }
}
