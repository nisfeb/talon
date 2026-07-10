package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable

/** iOS navigation back is an OS-level edge-swipe gesture, not an
 *  interceptable system button, so there's nothing to bind here. */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // No-op.
}
