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
import platform.UIKit.UIKeyboardDidHideNotification
import platform.UIKit.UIKeyboardDidShowNotification
import platform.UIKit.UIKeyboardFrameEndUserInfoKey
import platform.UIKit.UIKeyboardWillChangeFrameNotification
import platform.UIKit.valueWithCGRect

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
            if (!keyboardUp) keyboardIsDown()
        }
        centre.addObserverForName(UIKeyboardDidShowNotification, null, NSOperationQueue.mainQueue) { keyboardUp = true }
        centre.addObserverForName(UIKeyboardDidHideNotification, null, NSOperationQueue.mainQueue) {
            keyboardUp = false
            keyboardIsDown()
        }
        centre.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) {
            _foreground.value = false
        }
        // A rotation with the keyboard up leaves its inset behind: the
        // keyboard goes, its hide notification is lost in the turn, and
        // Compose keeps padding every screen for a keyboard that is not
        // there any more. Putting the keyboard down as the screen turns
        // makes the inset go with it. Only when the screen really turned:
        // the device turning (laid flat, or with rotation locked) put the
        // keyboard down mid-sentence.
        UIDevice.currentDevice.beginGeneratingDeviceOrientationNotifications()
        var turnedTo = interfaceOrientation()
        centre.addObserverForName(UIDeviceOrientationDidChangeNotification, null, NSOperationQueue.mainQueue) {
            platform.darwin.dispatch_after(
                platform.darwin.dispatch_time(platform.darwin.DISPATCH_TIME_NOW, 300_000_000L),
                platform.darwin.dispatch_get_main_queue(),
            ) {
                val now = interfaceOrientation()
                if (now != turnedTo) {
                    turnedTo = now
                    UIApplication.sharedApplication.sendAction(
                        platform.Foundation.NSSelectorFromString("resignFirstResponder"),
                        to = null,
                        from = null,
                        forEvent = null,
                    )
                }
            }
        }
    }

    private var keyboardUp = false

    /**
     * Tell Compose the keyboard is down, once UIKit says it is. Compose
     * 1.11 takes the keyboard's height only from frame-change animations,
     * and keeps a mid-animation height when one is cut short (a picker
     * presented, the app sent to the background): every screen then pads
     * for a keyboard that has gone, the white band along the bottom. An
     * empty end frame reads as height 0 at once.
     * ponytail: remove with Compose 1.13, which sets the final height on
     * every animation's end (KeyboardInsetsManager.ios.kt).
     */
    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    private fun keyboardIsDown() {
        NSNotificationCenter.defaultCenter.postNotificationName(
            UIKeyboardWillChangeFrameNotification,
            `object` = null,
            userInfo = mapOf<Any?, Any?>(
                UIKeyboardFrameEndUserInfoKey to platform.Foundation.NSValue.valueWithCGRect(platform.CoreGraphics.CGRectMake(0.0, 0.0, 0.0, 0.0)),
            ),
        )
    }

    /** The screen's orientation (not the device's), 0 when unknown. */
    private fun interfaceOrientation(): Long =
        UIApplication.sharedApplication.connectedScenes
            .filterIsInstance<platform.UIKit.UIWindowScene>()
            .firstOrNull()?.interfaceOrientation ?: 0L
}
