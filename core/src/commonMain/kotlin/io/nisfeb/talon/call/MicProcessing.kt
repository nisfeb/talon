package io.nisfeb.talon.call

import kotlin.concurrent.Volatile

/**
 * The microphone's software processing, per device: a headset in a
 * café and a laptop mic in a quiet room want different things, and
 * noise suppression eats music and some voices. Applied when an audio
 * source is created, so it takes effect on the next call or line.
 */
data class MicProcessing(
    val noiseSuppression: Boolean = true,
    val echoCancellation: Boolean = true,
    val autoGainControl: Boolean = true,
)

/** Where every platform's audio source reads the current choice. */
object MicProcessingSettings {
    @Volatile var current: MicProcessing = MicProcessing()
}
