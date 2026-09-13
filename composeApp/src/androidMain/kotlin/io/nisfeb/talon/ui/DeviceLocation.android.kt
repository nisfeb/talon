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
    // The grant lands here, and the request below waits for it. It
    // used to fire the prompt and return failure in the same breath, so
    // the error sat under the dialog and tapping Allow did nothing
    // until the person pressed the button a second time.
    val pending = remember { java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.CompletableDeferred<Boolean>?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> pending.getAndSet(null)?.complete(granted) }

    return remember(context) {
        suspend {
            val allowed = if (hasLocationPermission(context)) {
                true
            } else {
                val wait = kotlinx.coroutines.CompletableDeferred<Boolean>()
                pending.set(wait)
                launcher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                wait.await()
            }
            if (!allowed) {
                Result.failure(IllegalStateException("Location permission was not granted."))
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
