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
