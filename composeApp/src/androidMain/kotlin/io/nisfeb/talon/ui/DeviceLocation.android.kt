package io.nisfeb.talon.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberDeviceLocation(): DeviceLocation? {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* result swallowed; the picker's button is the retry */ }

    return remember(context) {
        suspend {
            if (!hasLocationPermission(context)) {
                launcher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                Result.failure(IllegalStateException("permission requested"))
            } else {
                val loc = fetchLastKnownLocation(context)
                if (loc == null) {
                    Result.failure(IllegalStateException("no fix"))
                } else {
                    Result.success(
                        HomePlace(
                            lat = loc.latitude,
                            lon = loc.longitude,
                            label = "This device",
                            fromGps = true,
                            // Coarse permission gives no altitude, and a
                            // network fix reports zero rather than
                            // nothing. Zero is sea level, which would
                            // quietly be wrong in Denver.
                            elevationMetres = loc.altitude.takeIf { loc.hasAltitude() },
                        ),
                    )
                }
            }
        }
    }
}
