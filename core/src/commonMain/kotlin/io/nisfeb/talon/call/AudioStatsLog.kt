package io.nisfeb.talon.call

/**
 * One compact log line per inbound audio stream, for the call report a
 * user attaches when a party line sounds wrong. The two symptoms it has
 * to tell apart:
 *
 *  - one member glitchy: `lost` / `conceal` climbing for that one ssrc
 *    while the others stay flat.
 *  - everyone slow and pitched-down: the playout clock is off, which the
 *    AudioDeviceModule won't report directly, but shows indirectly as
 *    the jitter buffer steadily stretching (`decel`) or compressing
 *    (`accel`) to chase a drifting rate.
 *
 * Pure, over plain attribute maps, so a test pins the formatting with no
 * live PeerConnection. A field the report omits prints `?` rather than
 * dropping the line — a missing field is itself a finding.
 */
fun audioStatsLines(inboundAudio: List<Map<String, Any?>>): List<String> =
    inboundAudio.map { a ->
        fun v(key: String): String = a[key]?.toString() ?: "?"
        "audio-in ssrc=${v("ssrc")} recv=${v("packetsReceived")} lost=${v("packetsLost")} " +
            "jitter=${v("jitter")} jbuf=${v("jitterBufferDelay")} conceal=${v("concealmentEvents")} " +
            "accel=${v("removedSamplesForAcceleration")} decel=${v("insertedSamplesForDeceleration")} " +
            "rate=${v("clockRate")} level=${v("audioLevel")}"
    }
