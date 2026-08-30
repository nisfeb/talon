package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable

/**
 * Permission to open the camera, asked at the moment it is wanted.
 *
 * Not folded into the engine-creation gate the microphone uses: the
 * mic is needed for every call, so it can be demanded up-front, while
 * the camera is turned on mid-call or never. Prompting for it when a
 * voice call starts would be asking for something most calls never do.
 */
interface CameraPermission {
    /** Whether the camera can be opened right now. */
    val granted: Boolean

    /** Ask for it. The answer lands via [granted] on a recomposition. */
    fun request()

    companion object {
        /** For platforms that ask at capture time, or not at all. */
        val Granted = object : CameraPermission {
            override val granted = true
            override fun request() {}
        }
    }
}

@Composable
expect fun rememberCameraPermission(): CameraPermission
