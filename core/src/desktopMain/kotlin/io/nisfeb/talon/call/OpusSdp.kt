package io.nisfeb.talon.call

/**
 * Returns [sdp] with `maxaveragebitrate=[bps]` on every Opus fmtp line,
 * adding the fmtp line when the offer/answer has none. libwebrtc takes
 * the encoder's target bitrate from the *remote* description, so this
 * is applied to what the other side sent before it is set.
 */
internal fun withOpusBitrate(sdp: String, bps: Int): String {
    val pts = Regex("""a=rtpmap:(\d+) opus/48000""").findAll(sdp).map { it.groupValues[1] }.toList()
    if (pts.isEmpty()) return sdp
    val eol = if ("\r\n" in sdp) "\r\n" else "\n"
    var out = sdp
    for (pt in pts) {
        val fmtp = Regex("""(?m)^a=fmtp:$pt ([^\r\n]*)""")
        out = if (fmtp.containsMatchIn(out)) {
            fmtp.replace(out) { m ->
                val kept = m.groupValues[1].split(';').filter { it.isNotBlank() && !it.trim().startsWith("maxaveragebitrate=") }
                "a=fmtp:$pt " + (kept + "maxaveragebitrate=$bps").joinToString(";")
            }
        } else {
            Regex("""(?m)^a=rtpmap:$pt opus/48000[^\r\n]*""").replace(out) { m ->
                m.value + eol + "a=fmtp:$pt maxaveragebitrate=$bps"
            }
        }
    }
    return out
}
