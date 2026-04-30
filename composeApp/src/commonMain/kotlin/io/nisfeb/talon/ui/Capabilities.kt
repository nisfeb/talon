package io.nisfeb.talon.ui

/**
 * Per-platform feature flags. A flag is `true` only when the platform
 * has the infrastructure to support the feature end-to-end; UI gates
 * itself on these so half-implemented features don't surface broken.
 *
 * Today these mirror the port plan's "out of scope on desktop" list.
 * Stage F or beyond can flip individual flags as features get the
 * platform glue they need.
 */
expect val isDailyDigestSupported: Boolean
expect val isVoiceMessagesSupported: Boolean
expect val isOnDeviceAiSupported: Boolean

/**
 * Per-feature supported predicate. The [isOnDeviceAiSupported] flag
 * gates whether the on-device-AI section of SettingsScreen renders
 * at all; this finer predicate then hides individual toggles whose
 * platform impl hasn't landed (e.g. EntityActions wants ML Kit on
 * Android, no equivalent on desktop yet).
 */
expect fun isOnDeviceAiFeatureSupported(
    feature: io.nisfeb.talon.ai.AiSettings.Feature,
): Boolean
