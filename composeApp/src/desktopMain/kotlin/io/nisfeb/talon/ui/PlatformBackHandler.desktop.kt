package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable

// Desktop has no system back button. Window-close goes through the
// AWT WindowListener wired in Main.kt, not through this shim.
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // No-op.
}
