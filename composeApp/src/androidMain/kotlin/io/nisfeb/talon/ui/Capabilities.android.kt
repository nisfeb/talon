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
