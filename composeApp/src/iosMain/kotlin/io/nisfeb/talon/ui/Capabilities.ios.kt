package io.nisfeb.talon.ui

import platform.UIKit.UIDevice

// iOS capability matrix. On-device AI (MediaPipe / DJL-ONNX) has no iOS
// backend, so every on-device feature gates off; the cloud Assistant is
// pure HTTP and works. Background scheduling (digest / loops) would need
// BGTaskScheduler wiring that doesn't exist yet, so those gate off too.
// Touch affordances (swipe-nav, tap-to-open-menu) are on.

actual val isDailyDigestSupported: Boolean = false
actual val isVoiceMessagesSupported: Boolean = false
actual val isOnDeviceAiSupported: Boolean = false
actual val isAssistantSupported: Boolean = true
actual val isLoopsSupported: Boolean = false
actual val isBackgroundSchedulingSupported: Boolean = false
actual val isQrLoginScanSupported: Boolean = false
actual val isTouchSwipeNavSupported: Boolean = true
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

// No CallKit/PushKit by design; UIBackgroundModes audio only sustains
// a call already in progress. Backgrounded, the app can't hear a ring.
actual val isBackgroundCallRingSupported: Boolean = false

// Supplied by MainViewController's edge strip -> IosBackDispatcher.
actual val isEdgeSwipeBackSupported: Boolean = true

// Apple Color Emoji is the system default; the span was a no-op
// that cost the text field its paste menu.
actual val needsEmojiFontSpans: Boolean = false

// No rich-content hook on iOS; the composer shows a paste button.
actual val needsManualImagePaste: Boolean = true
actual val isImmersiveCallSupported: Boolean = true
actual val isUrbWebViewSupported: Boolean = true
