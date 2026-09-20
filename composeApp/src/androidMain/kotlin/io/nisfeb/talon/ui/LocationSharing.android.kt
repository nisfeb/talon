package io.nisfeb.talon.ui

import android.Manifest
import android.location.Geocoder
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.nisfeb.talon.orrery.LocationWatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicReference

/**
 * The switch's side of [LocationWatch]: the prompts, then the listening.
 * Location first, then location all the time, asked for on its own as
 * Android 11 and later require; there the system shows its settings
 * page for it, and the answer arrives when the owner comes back.
 */
actual fun stopLocationSharing() = LocationWatch.stop()

@Composable
actual fun rememberLocationSharing(): LocationSharing? {
    val ctx = LocalContext.current.applicationContext
    // The grant lands here, and start() waits for it, as the dial's
    // location request does.
    val pending = remember { AtomicReference<CompletableDeferred<Boolean>?>(null) }
    DisposableEffect(Unit) { onDispose { pending.getAndSet(null)?.cancel() } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        pending.getAndSet(null)?.complete(r.values.any { it })
    }
    return remember(ctx) {
        object : LocationSharing {
            override val on: StateFlow<Boolean> = LocationWatch.on(ctx)
            override fun allowed() = LocationWatch.allowed(ctx)
            override val names: Boolean = Geocoder.isPresent()

            private suspend fun ask(vararg permissions: String): Boolean {
                val wait = CompletableDeferred<Boolean>()
                pending.getAndSet(wait)?.cancel()
                launcher.launch(arrayOf(*permissions))
                return wait.await()
            }

            override suspend fun start(): Boolean {
                if (!hasLocationPermission(ctx) && !ask(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) return false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    !LocationWatch.granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) &&
                    !ask(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                ) return false
                LocationWatch.set(ctx, true)
                // Orrery hears where the owner is now, not only after the first move.
                fetchLastKnownLocation(ctx)?.let { LocationWatch.send(ctx, it) }
                return true
            }

            override fun stop() = LocationWatch.set(ctx, false)
        }
    }
}
