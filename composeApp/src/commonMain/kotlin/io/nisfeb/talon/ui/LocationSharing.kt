package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.StateFlow

/**
 * Telling orrery where the owner is, from the phone, when they move a
 * few hundred metres: a place the ship knows, else a short place name,
 * never the coordinates. The platform wakes Talon for the move, so no
 * timer runs and a phone that stays put costs nothing.
 */
interface LocationSharing {
    /** The switch, per device. */
    val on: StateFlow<Boolean>

    /** Whether the device lets Talon hear moves with the app closed. */
    fun allowed(): Boolean

    /** Whether the device can turn a position into a place name; without it only places orrery knows are sent. */
    val names: Boolean

    /** Ask for what hearing moves in the background needs, then listen. False when it was refused. */
    suspend fun start(): Boolean

    fun stop()
}

/** The platform's, or null where [isLocationSharingSupported] is false. */
@Composable
expect fun rememberLocationSharing(): LocationSharing?

/**
 * Stop listening, from outside the screen. The switch lives under the
 * orrery pipe, so turning the pipe off takes the switch off the screen:
 * left listening, the phone went on waking for moves it had nowhere to
 * send, and nothing on the screen could stop it. A platform that does
 * not share location does nothing here.
 */
expect fun stopLocationSharing()
