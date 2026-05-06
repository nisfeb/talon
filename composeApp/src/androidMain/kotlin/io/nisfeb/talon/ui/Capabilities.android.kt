package io.nisfeb.talon.ui

actual val isDailyDigestSupported: Boolean = true
actual val isVoiceMessagesSupported: Boolean = true
actual val isOnDeviceAiSupported: Boolean = true

actual val platformLabel: String = "Android ${android.os.Build.VERSION.RELEASE}"

actual fun isOnDeviceAiFeatureSupported(
    feature: io.nisfeb.talon.ai.AiSettings.Feature,
): Boolean = true
