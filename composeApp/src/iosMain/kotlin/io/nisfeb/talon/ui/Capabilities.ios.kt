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

// Trunkline calls: WebRTC.xcframework engine pending (design roadmap v2).
actual val isCallsSupported: Boolean = false

// Supplied by MainViewController's edge strip -> IosBackDispatcher.
actual val isEdgeSwipeBackSupported: Boolean = true
