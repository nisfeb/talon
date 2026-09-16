package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusRestricted
import platform.CoreLocation.kCLLocationAccuracyKilometer
import platform.Foundation.NSError
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * CoreLocation, asked once and then let go.
 *
 * Kilometre accuracy on purpose. The dial wants to know which sky you
 * are under, and a town is enough for that; asking for metres would
 * spin the GPS radio to no benefit and would be a bigger thing to
 * grant than the feature deserves.
 *
 * A denial is an ordinary answer, not an error: it fails, the picker
 * says so, and the person types a place instead.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberDeviceLocation(): DeviceLocation? = remember {
    suspend { requestFix() }
}

/**
 * CLLocationManager holds its delegate *weakly*, and the manager
 * itself is a local. With nothing on the Kotlin side referring to
 * either, both can be collected between the request and the fix, and
 * the answer simply never arrives — a hang rather than a failure,
 * which is the worst kind. This set is that reference, and [settle]
 * drops it.
 *
 * Touched only from the main thread: the picker launches into the
 * composition's scope and CoreLocation calls back on the run loop it
 * was created on.
 */
private val inFlight = mutableSetOf<Any>()

@OptIn(ExperimentalForeignApi::class)
private suspend fun requestFix(): Result<HomePlace> =
    suspendCancellableCoroutine { cont ->
        val manager = CLLocationManager()
        manager.desiredAccuracy = kCLLocationAccuracyKilometer

        // The delegate outlives this frame, so it holds itself alive
        // through the manager's own reference and clears both once it
        // has answered. Answering twice would resume a finished
        // continuation, which is a crash rather than a wrong reading.
        var settled = false
        var held: NSObject? = null
        fun settle(r: Result<HomePlace>) {
            if (settled) return
            settled = true
            manager.stopUpdatingLocation()
            manager.delegate = null
            held?.let { inFlight.remove(it) }
            inFlight.remove(manager)
            held = null
            cont.resume(r)
        }

        val delegate = object : NSObject(), CLLocationManagerDelegateProtocol {
            override fun locationManager(
                manager: CLLocationManager,
                didUpdateLocations: List<*>,
            ) {
                val loc = didUpdateLocations.lastOrNull() as? CLLocation
                if (loc == null) {
                    settle(Result.failure(IllegalStateException("no fix")))
                    return
                }
                val (lat, lon) = loc.coordinate.useContents { latitude to longitude }
                settle(
                    Result.success(
                        HomePlace(
                            lat = lat,
                            lon = lon,
                            label = "This device",
                            fromGps = true,
                            // A negative vertical accuracy is CoreLocation
                            // saying the altitude is meaningless. Taking it
                            // anyway would put somebody at sea level in
                            // Denver and shorten their day.
                            elevationMetres = loc.altitude.takeIf { loc.verticalAccuracy >= 0 },
                        ),
                    ),
                )
            }

            override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
                settle(Result.failure(IllegalStateException(didFailWithError.localizedDescription)))
            }

            override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
                when (manager.authorizationStatus) {
                    kCLAuthorizationStatusAuthorizedWhenInUse,
                    kCLAuthorizationStatusAuthorizedAlways,
                    -> manager.requestLocation()
                    kCLAuthorizationStatusDenied,
                    kCLAuthorizationStatusRestricted,
                    -> settle(Result.failure(IllegalStateException("location refused")))
                    // Still undetermined: the prompt is up, and the next
                    // call to this same method carries the answer.
                    else -> Unit
                }
            }
        }
        held = delegate
        inFlight += delegate
        inFlight += manager
        manager.delegate = delegate
        // Fires the authorization callback immediately when the answer
        // is already known, so the authorized path does not wait on a
        // prompt that will never appear.
        manager.requestWhenInUseAuthorization()

        cont.invokeOnCancellation { settle(Result.failure(IllegalStateException("cancelled"))) }
    }
