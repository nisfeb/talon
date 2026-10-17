package io.nisfeb.talon.ai

/**
 * A recorded party line's words, one speaker's audio at a time. The SFU
 * gives one track per speaker, so each is transcribed on its own: that is
 * where the speaker comes from, with no diarization. The audio goes to
 * Armillary's transcription model ([ArmillaryTranscribe]) a stretch of
 * speech at a time ([CallRecordingPublisher.speechSpans]).
 */
object CallTranscribe {
    /** One transcribed span: milliseconds from the start of the clip, and the text. The caller adds the speaker. */
    data class Segment(val startMs: Long, val text: String)
}
