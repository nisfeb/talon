package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.StateFlow

/**
 * Telling orrery where the owner is, from the phone, when they move a
 * few hundred metres: as an observation, a place the ship knows, else a
 * short place name, never the coordinates; and as the position, to the
 * metre, for the leave alert and live shares (orrery 69, 88). The
 * platform wakes Talon for the move, so no timer runs and a phone that
 * stays put costs nothing; a live share asks a fix a minute.
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
 * Location sharing as the orrery pipe reaches it, from outside the
 * screen. The switch lives under the pipe, so the pipe going off takes
 * the switch off the screen: left listening, the phone went on waking
 * for moves it had nowhere to send, and nothing on the screen could
 * stop it. [NoopLocationControl] where a platform shares no location.
 */
interface LocationControl {
    /** Off, the switch with it: the pipe turned off, or its key refused. */
    fun stop()

    /**
     * Not listening for now, or listening again, the switch kept as it
     * is: for a ship with no pipe, which has nowhere to send a move and
     * no switch on its screen. Turning the switch off there turned it
     * off for the ship that had a pipe, since there is one switch.
     */
    fun pause(paused: Boolean)

    /**
     * A fix a minute until [untilMs], while a live share runs (orrery 88);
     * null when none does. The phone's to keep: the ship decides when a
     * share ends, and the phone reads it again before then.
     */
    fun live(untilMs: Long?) = Unit
}

object NoopLocationControl : LocationControl {
    override fun stop() = Unit
    override fun pause(paused: Boolean) = Unit
}
