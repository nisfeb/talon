package io.nisfeb.talon.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Composable
actual fun rememberDeviceLocation(): DeviceLocation? {
    val context = LocalContext.current
    // The grant lands here, and the request below waits for it. It
    // used to fire the prompt and return failure in the same breath, so
    // the error sat under the dialog and tapping Allow did nothing
    // until the person pressed the button a second time.
    val pending = remember { java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.CompletableDeferred<Boolean>?>(null) }
    // A caller whose composable leaves while the prompt is up must not
    // wait on an answer that can no longer arrive.
    DisposableEffect(Unit) { onDispose { pending.getAndSet(null)?.cancel() } }
    // One prompt at a time: a second call while the first is up would
    // replace its deferred and leave the first caller waiting forever.
    val promptLock = remember { Mutex() }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> pending.getAndSet(null)?.complete(granted) }

    return remember(context) {
        suspend {
            val allowed = if (hasLocationPermission(context)) {
                true
            } else {
                promptLock.withLock {
                    // Rechecked inside the lock: the caller before us may
                    // have just won the grant.
                    if (hasLocationPermission(context)) {
                        true
                    } else {
                        val wait = kotlinx.coroutines.CompletableDeferred<Boolean>()
                        pending.set(wait)
                        launcher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                        wait.await()
                    }
                }
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
