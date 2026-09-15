package io.nisfeb.talon.notify

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlin.concurrent.Volatile

/** How this platform takes back a notification about a key (a whom, or "mail:<thread>"). No-op where nothing is posted. */
val LocalNotificationClearer = staticCompositionLocalOf<(String) -> Unit> { {} }

/** The keys on screen while the app is in front, so a push about one can be dropped on arrival. */
object ShownConversation {
    // ponytail: written only from composition (one thread), read from a push receiver; a volatile list, not a lock.
    @Volatile
    var keys: List<String> = emptyList()
        private set

    internal fun shown(key: String) { keys = keys + key }
    internal fun hidden(key: String) { keys = keys - key }
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
