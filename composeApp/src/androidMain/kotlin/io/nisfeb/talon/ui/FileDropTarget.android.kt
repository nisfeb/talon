package io.nisfeb.talon.ui

import androidx.compose.ui.Modifier

/**
 * Android stubs for the cross-platform drop / paste hooks. Touch
 * Android doesn't expose a Compose-friendly external-drag API
 * today; clipboard image paste needs a `ContentReceiver` /
 * EditText interop that's deferred to a follow-up rc. Both calls
 * are silent no-ops here so commonMain code that wires them stays
 * cross-target without conditional gating.
 */
actual fun Modifier.fileDropTarget(
    enabled: Boolean,
    onFiles: (List<DroppedFile>) -> Unit,
): Modifier = this

actual fun readClipboardImageOrNull(): DroppedFile? = null
