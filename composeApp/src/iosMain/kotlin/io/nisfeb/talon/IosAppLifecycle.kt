package io.nisfeb.talon

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationState

/**
 * Whether the app is in front, from UIKit's own notifications.
 *
 * iOS suspends a backgrounded app and lets its sockets die; on the
 * way back the event stream is a corpse until something notices. The
 * window-focus signal the shared shell watches never flips on a phone
 * with one window, so this is the signal that does: true on becoming
 * active, false on entering the background.
 */
object IosAppLifecycle {
    private val _foreground = MutableStateFlow(true)
    val foreground: StateFlow<Boolean> = _foreground

    private var observing = false

    fun observe() {
        if (observing) return
        observing = true
        // Seed from the real state: observe() runs after launch, and an
        // app started into the background (a VoIP push, say) would
        // otherwise read foreground=true until the first notification.
        _foreground.value =
            UIApplication.sharedApplication.applicationState != UIApplicationState.UIApplicationStateBackground
        val centre = NSNotificationCenter.defaultCenter
        centre.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) {
            _foreground.value = true
        }
        centre.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) {
            _foreground.value = false
        }
    }
}
