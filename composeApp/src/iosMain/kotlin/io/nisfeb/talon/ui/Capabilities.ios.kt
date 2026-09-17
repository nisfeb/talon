package io.nisfeb.talon.ui

import platform.UIKit.UIDevice

// iOS capability matrix. On-device AI (MediaPipe / DJL-ONNX) has no iOS
// backend, so every on-device feature gates off; the cloud Assistant is
// pure HTTP and works. Loops run while the app is up; true background
// scheduling (digest) would need BGTaskScheduler wiring that doesn't
// exist yet, so that gates off. Touch affordances (swipe-nav,
// tap-to-open-menu) are on.

actual val isVoiceMessagesSupported: Boolean = true
actual val isOnDeviceAiSupported: Boolean = false
actual val isAssistantSupported: Boolean = true
actual val isLoopsSupported: Boolean = true
actual val isBackgroundSchedulingSupported: Boolean = false
actual val isQrScanSupported: Boolean = true
actual val isLocalTriageSupported: Boolean = false
actual val isLocalCometSupported: Boolean = false
actual val isTouchSwipeNavSupported: Boolean = true
actual val hasSoftKeyboard: Boolean = true
actual val isDictationSupported: Boolean = true
actual val isTapToOpenMenuSupported: Boolean = true

actual val platformLabel: String = "iOS ${UIDevice.currentDevice.systemVersion}"

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

// CallKit/PushKit live in the Xcode target (CallPush.swift): a PushKit
// VoIP token reaches the relay through IosVoipBridge/IosPushTokenProvider,
// so a backgrounded phone hears a ring. UIBackgroundModes audio sustains
// a call already in progress.
actual val isBackgroundCallRingSupported: Boolean = true

// Supplied by MainViewController's edge strip -> IosBackDispatcher.
actual val isVideoCallsSupported: Boolean = true
actual val isPartyVideoSupported: Boolean = true
actual val isCameraSwitchSupported: Boolean = true

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
