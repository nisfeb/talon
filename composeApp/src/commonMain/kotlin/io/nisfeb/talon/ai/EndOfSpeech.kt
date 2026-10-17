package io.nisfeb.talon.ai

import kotlin.math.sqrt

/**
 * When a dictation has been said: the owner speaks and stops, and nothing
 * is pressed to end it. Fed the recording a frame at a time (16-bit
 * little-endian mono PCM), it says whether to keep listening.
 *
 * Listening ends after [endSilenceMs] of quiet once speech was heard, after
 * [giveUpMs] with no speech at all ([heard] stays false: nothing to send),
 * or at [maxMs] whatever is happening.
 *
 * Speech is a frame at least as loud as [minSpeechRms] and as [overFloor]
 * times the quietest frame so far, which is the room. The quietest frame
 * and not the first: the owner may start talking before the first frame
 * lands.
 *
 * ponytail: an energy gate, not a voice model. Steady loud noise (a car,
 * a fan) raises the floor with it; a voice that does not rise [overFloor]
 * above it is not heard, so the dictation ends early or hears nothing, and
 * [maxMs] ends one in a room that never goes quiet. The thresholds are
 * the calibration knobs; WebRTC's VAD is the upgrade if they fail on the
 * phone.
 */
class EndOfSpeech(
    private val sampleRate: Int,
    private val endSilenceMs: Long = 1_800,
    private val giveUpMs: Long = 8_000,
    private val maxMs: Long = 120_000,
    private val minSpeechRms: Double = 500.0,
    private val overFloor: Double = 3.0,
) {
    /** Whether any speech was heard. */
    var heard: Boolean = false
        private set

    /** The last frame's loudness, 0 to 1, for a level meter. */
    var level: Float = 0f
        private set

    /** The loudest frame so far, for calibrating the thresholds on a device. */
    var peak: Double = 0.0
        private set

    /** The quietest frame so far: the room. */
    var floor: Double = Double.MAX_VALUE
        private set

    private var elapsedMs = 0L
    private var quietMs = 0L

    /** Take the first [bytes] of [pcm]; true to keep listening. */
    fun offer(pcm: ByteArray, bytes: Int = pcm.size): Boolean {
        val samples = bytes / 2
        if (samples == 0) return true
        val frameMs = samples * 1000L / sampleRate
        elapsedMs += frameMs
        val r = rms(pcm, samples)
        level = (r / LOUD_RMS).toFloat().coerceIn(0f, 1f)
        peak = maxOf(peak, r)
        // Digital silence is the mic starting up, not the room: as the
        // floor it would make any steady noise speech, and a dictation in
        // a car would run to maxMs.
        if (r >= 1.0) floor = minOf(floor, r)
        if (r >= minSpeechRms && r >= floor * overFloor) {
            heard = true
            quietMs = 0
        } else {
            quietMs += frameMs
        }
        return when {
            elapsedMs >= maxMs -> false
            heard -> quietMs < endSilenceMs
            else -> elapsedMs < giveUpMs
        }
    }

    companion object {
        /** Where the level meter reads full: a voice close to the phone. */
        private const val LOUD_RMS = 6_000.0

        /** The root mean square of [samples] 16-bit little-endian samples, from byte [from]. */
        fun rms(pcm: ByteArray, samples: Int, from: Int = 0): Double {
            var sum = 0.0
            for (i in 0 until samples) {
                val s = (pcm[from + 2 * i].toInt() and 0xFF) or (pcm[from + 2 * i + 1].toInt() shl 8)
                sum += s.toDouble() * s
            }
            return sqrt(sum / samples)
        }
    }
}
