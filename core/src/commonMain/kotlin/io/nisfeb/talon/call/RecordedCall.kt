package io.nisfeb.talon.call

/**
 * The captured audio of one party-line recording.
 *
 * One PCM buffer per speaker (16-bit little-endian mono at
 * [sampleRate]), keyed by their patp. Each buffer is padded with
 * leading silence to the moment recording started, so all speakers
 * share t=0 — that is what lets per-speaker transcripts merge into one
 * timeline and lets [PcmMix] mix them without per-track offsets.
 */
data class RecordedCall(
    val clips: Map<String, ByteArray>,
    val sampleRate: Int,
) {
    val isEmpty: Boolean get() = clips.isEmpty() || clips.values.all { it.isEmpty() }
}
