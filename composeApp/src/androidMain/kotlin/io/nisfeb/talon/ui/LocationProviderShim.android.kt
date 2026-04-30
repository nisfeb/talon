package io.nisfeb.talon.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
fun rememberLocationProvider(): LocationProvider? {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* result swallowed; user retries /loc */ }

    return remember(context) {
        suspend {
            if (!hasLocationPermission(context)) {
                launcher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                Result.failure(IllegalStateException("/loc: permission requested — try again"))
            } else {
                val loc = fetchLastKnownLocation(context)
                if (loc == null) Result.failure(IllegalStateException("/loc: no fix available"))
                else Result.success(loc.latitude to loc.longitude)
            }
        }
    }
}
