package io.nisfeb.talon.call

import org.webrtc.PeerConnection

/**
 * A connection's audio levels, the far end's and our microphone's, for
 * who is speaking: a party line's links and a 1:1 call read them alike.
 *
 * Android's RtpReceiver has no getSynchronizationSources, so the level
 * comes from inbound-rtp stats. getStats is async, so a poll refreshes
 * this and a read returns what the last one saw; the caller polls at
 * 250 ms anyway. Nothing is asked once [closed].
 */
internal class StatsLevels(private val pc: PeerConnection, private val closed: () -> Boolean) {
    @Volatile private var lastLevel: Float? = null
    @Volatile private var statsInFlight = false
    @Volatile private var lastLocalLevel: Float? = null
    @Volatile private var localInFlight = false

    fun remote(): Float? = level("inbound-rtp")

    /**
     * Our own microphone, from media-source.
     *
     * Not outbound-rtp: that describes what was sent, which is
     * silence-suppressed, so a quiet talker reads as nothing at all.
     * media-source is what the microphone actually heard.
     */
    fun local(): Float? = level("media-source")

    private fun level(type: String): Float? {
        val local = type == "media-source"
        val busy = if (local) localInFlight else statsInFlight
        if (!busy && !closed()) {
            if (local) localInFlight = true else statsInFlight = true
            runCatching {
                pc.getStats { report ->
                    var found: Float? = null
                    for (stat in report.statsMap.values) {
                        if (stat.type == type) {
                            (stat.members["audioLevel"] as? Number)?.let {
                                found = it.toFloat()
                            }
                        }
                    }
                    if (local) {
                        lastLocalLevel = found
                        localInFlight = false
                    } else {
                        lastLevel = found
                        statsInFlight = false
                    }
                }
            }.onFailure { if (local) localInFlight = false else statsInFlight = false }
        }
        return if (local) lastLocalLevel else lastLevel
    }
}
