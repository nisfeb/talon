package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.StateFlow

/**
 * Sending orrery a summary of each day's health from the phone's health
 * store: steps, workouts and sleep, totals and times only. Which days
 * go up and what each says is [io.nisfeb.talon.orrery.healthPlan] and
 * [io.nisfeb.talon.orrery.healthBody]; this is the switch and the asking.
 */
interface HealthSharing {
    /** The switch, per device. */
    val on: StateFlow<Boolean>

    /** Whether the phone has a health store Talon can read. */
    fun available(): Boolean

    /** Whether Talon may read with the app closed, so today goes up on a timer and not only when the app opens. */
    suspend fun background(): Boolean

    /** Ask for reading steps, exercise and sleep, then send. False when it was refused. */
    suspend fun start(): Boolean

    fun stop()
}

/** The platform's, or null where [isHealthSharingSupported] is false. */
@Composable
expect fun rememberHealthSharing(): HealthSharing?
