// Desktop actual for the commonMain `BatteryExemptionBanner` expect.
// Android-only feature; renders nothing on desktop.
package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun BatteryExemptionBanner(modifier: Modifier) {
    // No-op on desktop.
}
