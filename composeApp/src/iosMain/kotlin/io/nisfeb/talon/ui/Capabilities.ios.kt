package io.nisfeb.talon.ui

import platform.StoreKit.SKPaymentQueue
import platform.UIKit.UIDevice
import platform.UIKit.UIUserInterfaceIdiomPhone

// iOS capability matrix. The semantic-search embedder (isOnDeviceAiSupported,
// MediaPipe / DJL-ONNX) has no iOS backend and gates off, but local triage
// (isLocalTriageSupported) runs on Apple's system model and the cloud Assistant
// is pure HTTP, so both are on. Loops run while the app is up
// (isLoopsSupported); running them with the app closed would need
// BGTaskScheduler wiring that doesn't exist yet, so that gates off. Touch
// affordances (swipe-nav, tap-to-open-menu) are on.

actual val isVoiceMessagesSupported: Boolean = true
actual val isOnDeviceAiSupported: Boolean = false
actual val isAssistantSupported: Boolean = true
actual val isLoopsSupported: Boolean = true
actual val isBackgroundSchedulingSupported: Boolean = false
actual val isQrScanSupported: Boolean = true
actual val isLocalTriageSupported: Boolean = true
actual val isLocalCometSupported: Boolean = false
actual val isTouchSwipeNavSupported: Boolean = true
actual val hasSoftKeyboard: Boolean = true
actual val isDictationSupported: Boolean = true
actual val isQuickDictationSupported: Boolean = false
actual val isTapToOpenMenuSupported: Boolean = true

actual val platformLabel: String = "iOS ${UIDevice.currentDevice.systemVersion}"

actual val iosDeviceName: String =
    if (UIDevice.currentDevice.userInterfaceIdiom == UIUserInterfaceIdiomPhone) "iPhone" else "iPad"

actual fun isOnDeviceAiFeatureSupported(
    @Suppress("UNUSED_PARAMETER") feature: io.nisfeb.talon.ai.AiSettings.Feature,
): Boolean = false

// Trunkline calls: WebRTC lives in the Xcode target as a Swift Package,
// bridged through NativeRtcFactory. The flag says the platform is
// capable; App() still gates the controller on the host actually
// passing a factory, so a build without it shows no call UI.
actual val isCallsSupported: Boolean = true
actual val isCallRecordingSupported: Boolean = false
actual val isWindowFullScreenSupported: Boolean = false
actual val isWholeWindowZoom: Boolean = false

// CallKit/PushKit live in the Xcode target (CallPush.swift): a PushKit
// VoIP token reaches the relay through IosVoipBridge/IosPushTokenProvider,
// so a backgrounded phone hears a ring. UIBackgroundModes audio sustains
// a call already in progress.
actual val isBackgroundCallRingSupported: Boolean = true
// The relay's PushKit leg is offered at sign-in (isRelayNotificationSetupNeeded).
actual val needsPushForBackgroundRing: Boolean = false

actual val isVideoCallsSupported: Boolean = true
actual val isPartyVideoSupported: Boolean = true
// iOS: port pending (a ReplayKit broadcast extension).
actual val isScreenShareSupported: Boolean = false
actual val isCameraSwitchSupported: Boolean = true

// Compose's own left-edge pan, through PlatformBackHandler's navigation events.
actual val isEdgeSwipeBackSupported: Boolean = true

// Apple Color Emoji is the system default; the span was a no-op
// that cost the text field its paste menu.
actual val needsEmojiFontSpans: Boolean = false

// No rich-content hook on iOS; the composer shows a paste button.
actual val needsManualImagePaste: Boolean = true
actual val isImmersiveCallSupported: Boolean = true
actual val isUrbWebViewSupported: Boolean = true

/** As Android: a drawer, and the edge swipe to come back. */
actual val isDrawerNavigation: Boolean = true
actual val isTouchPrimary: Boolean = true

// Port pending: CLLocationManager significant-change monitoring.
actual val isLocationSharingSupported: Boolean = false
actual val isHealthSharingSupported: Boolean = false

/** The US storefront only. Read each time: the storefront follows the Apple ID's country. */
actual val isArmillaryPurchaseSupported: Boolean
    get() = outsideCheckoutAllowedOnAppStore(SKPaymentQueue.defaultQueue().storefront?.countryCode)

actual val isRelayNotificationSetupNeeded: Boolean = true

actual val isAppIconBadgeSupported: Boolean = true

/** Brave for iOS has had Leo's Bring your own model form since 1.88. */
actual val isBraveLeoSupported: Boolean = true

/**
 * A tel: link offers the call on an iPhone. An iPad has no dialer for it
 * to open (one paired with an iPhone could hand the call over, but the
 * tool would say a dialer opened), so only the phone idiom counts.
 */
actual val isPhoneDialSupported: Boolean =
    UIDevice.currentDevice.userInterfaceIdiom == UIUserInterfaceIdiomPhone
