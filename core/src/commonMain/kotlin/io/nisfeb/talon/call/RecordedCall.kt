package io.nisfeb.talon.call

/**
 * The captured audio of one party-line recording.
 *
 * One PCM buffer per speaker (16-bit little-endian mono), keyed by their
 * patp. Each buffer is padded with leading silence to the moment
 * recording started, so all speakers share t=0 — that is what lets
 * per-speaker transcripts merge into one timeline and lets [PcmMix] mix
 * them without per-track offsets.
 */
data class RecordedCall(
    val clips: Map<String, ByteArray>,
    /**
     * The rate a mixdown runs at — the highest rate any clip was
     * captured at. Clips below it must be resampled ([PcmResample])
     * before mixing; this is not automatically every clip's own rate.
     */
    val sampleRate: Int,
    /**
     * Each speaker's own capture rate. These genuinely differ: a desktop
     * mic is tapped straight off the capture device at its native rate
     * (44.1 kHz is common) while remote down-links arrive decoded at
     * 48 kHz. Encoding every clip at one rate pitch-shifts whoever
     * didn't match, which also garbles their transcript. Missing entry =
     * [sampleRate].
     */
    val rates: Map<String, Int> = emptyMap(),
) {
    val isEmpty: Boolean get() = clips.isEmpty() || clips.values.all { it.isEmpty() }

    /** The rate [ship]'s clip was actually captured at. */
    fun rateOf(ship: String): Int = rates[ship] ?: sampleRate
}
