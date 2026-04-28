// TEMPORARY DUPLICATE: ported in stub form from
// app/src/main/java/io/nisfeb/talon/ui/BatteryBanner.kt during the CMP
// desktop port (Task D4 prerequisite). The Android-only banner
// references Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS and
// PowerManager — both meaningless on desktop, so this is an
// expect/actual split. The androidMain actual reproduces the
// production banner; the desktop actual is a no-op.
package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun BatteryExemptionBanner(modifier: Modifier = Modifier)
