package io.nisfeb.talon

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceOrientationDidChangeNotification

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

    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
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
        // A rotation with the keyboard up leaves its inset behind: the
        // keyboard goes, its hide notification is lost in the turn, and
        // Compose keeps padding every screen for a keyboard that is not
        // there any more. That padding is the white band along the
        // bottom. Putting the keyboard down as the device turns makes
        // the inset go with it.
        UIDevice.currentDevice.beginGeneratingDeviceOrientationNotifications()
        centre.addObserverForName(UIDeviceOrientationDidChangeNotification, null, NSOperationQueue.mainQueue) {
            UIApplication.sharedApplication.sendAction(
                platform.Foundation.NSSelectorFromString("resignFirstResponder"),
                to = null,
                from = null,
                forEvent = null,
            )
        }
    }
}
