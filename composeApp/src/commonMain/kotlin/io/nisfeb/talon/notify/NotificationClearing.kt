package io.nisfeb.talon.notify

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlin.concurrent.Volatile

/** How this platform takes back a notification about a key (a whom, or "mail:<thread>"). No-op where nothing is posted. */
val LocalNotificationClearer = staticCompositionLocalOf<(String) -> Unit> { {} }

/** The keys on screen while the app is in front, so a push about one can be dropped on arrival. */
object ShownConversation {
    // ponytail: written only from composition (one thread), read from a push receiver; a volatile map, not a lock.
    // Reference-counted: two surfaces can show the same key at once
    // (a chat and its thread), and removing on the first hide leaked
    // the other's registration — pushes kept being dropped after it
    // closed.
    @Volatile
    private var counts: Map<String, Int> = emptyMap()

    val keys: Set<String>
        get() = counts.keys

    internal fun shown(key: String) {
        counts = counts + (key to (counts[key] ?: 0) + 1)
    }

    internal fun hidden(key: String) {
        val n = counts[key] ?: return
        counts = if (n <= 1) counts - key else counts + (key to n - 1)
    }
}

/**
 * While this is on screen and the app is in front, notifications about
 * [key] are cleared: when it appears, and again on every return to the
 * app, which is the case a notification that arrived while away used
 * to survive.
 */
@Composable
fun ClearNotificationsWhileShown(key: String) {
    val clear = LocalNotificationClearer.current
    LifecycleResumeEffect(key, clear) {
        ShownConversation.shown(key)
        clear(key)
        onPauseOrDispose { ShownConversation.hidden(key) }
    }
}
