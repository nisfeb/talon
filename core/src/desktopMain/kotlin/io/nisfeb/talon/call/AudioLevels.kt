package io.nisfeb.talon.call

import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCStatsReport

/**
 * A connection's audio levels, the far end's and our microphone's, for
 * who is speaking: a party line's links and a 1:1 call read them alike.
 * [onRemoteStats] sees each inbound report (the party line's audio
 * diagnostics).
 */
internal class AudioLevels(
    private val pc: RTCPeerConnection,
    private val onRemoteStats: (RTCStatsReport?) -> Unit = {},
) {
    private val lastStatsLevel = kotlinx.atomicfu.atomic(-1f)
    private val lastLocalLevel = kotlinx.atomicfu.atomic(-1f)
    private val statsInFlight = kotlinx.atomicfu.atomic(false)
    private val localInFlight = kotlinx.atomicfu.atomic(false)

    /**
     * Remote audio level, or null when it can't be read.
     *
     * Two sources, because the cheap one is not always there.
     * getSynchronizationSources reports the level carried in RTP's
     * own header extension: free, but only populated when that
     * extension was negotiated. Galene's down streams did not carry
     * it, so desktop showed nobody speaking while Android, which
     * reads inbound-rtp statistics instead, showed everyone.
     *
     * Fall back to the same statistic Android uses. It is
     * asynchronous, so a call kicks off a refresh and returns what
     * the last one saw; the caller polls at 4Hz anyway.
     */
    fun remote(): Float? =
        runCatching {
            pc.receivers.firstNotNullOfOrNull { r ->
                // > 0, not just non-null. audioLevel is a primitive
                // double, so an existing source whose header extension
                // was never negotiated reports 0.0, which is not null,
                // so the fallback below never ran and desktop reported
                // silence forever. Nobody is audible at exactly zero.
                r.synchronizationSources?.firstOrNull()?.audioLevel?.toFloat()
                    ?.takeIf { it > 0f }
            }
        }.getOrNull() ?: stats(local = false)

    /**
     * Our own microphone level, from the MEDIA_SOURCE statistic.
     *
     * Not inbound-rtp: an up link has no receivers, and outbound-rtp
     * describes what was sent rather than what the microphone heard,
     * which is silence-suppressed, so a quiet talker reads as nothing.
     */
    fun local(): Float? = stats(local = true)

    private fun stats(local: Boolean): Float? {
        val inFlight = if (local) localInFlight else statsInFlight
        val last = if (local) lastLocalLevel else lastStatsLevel
        val want =
            if (local) dev.onvoid.webrtc.RTCStatsType.MEDIA_SOURCE
            else dev.onvoid.webrtc.RTCStatsType.INBOUND_RTP
        if (inFlight.compareAndSet(expect = false, update = true)) {
            runCatching {
                pc.getStats { report ->
                    var level = -1f
                    report?.stats?.values?.forEach { st ->
                        if (st?.type == want) {
                            (st.attributes?.get("audioLevel") as? Number)?.let {
                                level = it.toFloat()
                            }
                        }
                    }
                    last.value = level
                    if (!local) onRemoteStats(report)
                    inFlight.value = false
                }
            }.onFailure { inFlight.value = false }
        }
        return last.value.takeIf { it >= 0f }
    }
}
