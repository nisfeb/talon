package io.nisfeb.talon.ui

actual val isDailyDigestSupported: Boolean = true
actual val isVoiceMessagesSupported: Boolean = true
actual val isOnDeviceAiSupported: Boolean = true
actual val isAssistantSupported: Boolean = true
actual val isLoopsSupported: Boolean = true
actual val isBackgroundSchedulingSupported: Boolean = true
actual val isQrLoginScanSupported: Boolean = true
actual val isTouchSwipeNavSupported: Boolean = true
actual val isTapToOpenMenuSupported: Boolean = true

actual val platformLabel: String = "Android ${android.os.Build.VERSION.RELEASE}"

actual fun isOnDeviceAiFeatureSupported(
    feature: io.nisfeb.talon.ai.AiSettings.Feature,
): Boolean = true

// Trunkline calls: libwebrtc via getstream build (AndroidCallEngine).
actual val isCallsSupported: Boolean = true
actual val isCallRecordingSupported: Boolean = true

// UnifiedPush wakes the process, so a ring lands with the app closed.
actual val isBackgroundCallRingSupported: Boolean = true

// The system back gesture already owns the left edge here.
actual val isVideoCallsSupported: Boolean = true
actual val isPartyVideoSupported: Boolean = true
actual val isCameraSwitchSupported: Boolean = true

actual val isEdgeSwipeBackSupported: Boolean = false

// Android draws colour emoji in the default family already.
actual val needsEmojiFontSpans: Boolean = false

// Handled by the field's content receiver.
actual val needsManualImagePaste: Boolean = false
actual val isImmersiveCallSupported: Boolean = true
actual val isUrbWebViewSupported: Boolean = true
