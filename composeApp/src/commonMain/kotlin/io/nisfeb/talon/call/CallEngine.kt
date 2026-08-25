package io.nisfeb.talon.call

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A complete (non-trickle) session description plus the DTLS
 * certificate fingerprint the peer should pin. sdp already contains
 * every gathered ICE candidate — see design D3: one offer poke, one
 * answer poke, no trickling over ames.
 */
data class SessionDesc(val sdp: String, val fingerprint: String)

/** One STUN/TURN endpoint the ship advertises (scry /x/ice). Empty
 *  user/cred for STUN. */
data class IceServer(val url: String, val user: String, val cred: String)

enum class MediaState { Idle, Gathering, Connecting, Live, Failed, Closed }

/**
 * One call's media half. Created per call by [CallEngineProvider];
 * the platform impl wraps libwebrtc (webrtc-java on desktop). The
 * signaling half (CallController) never touches WebRTC types.
 *
 * Same-shape-interface pattern (CLAUDE.md #2): common interface,
 * platform impls, Noop fallback for platforms without an engine.
 */
interface CallEngine {
    val state: StateFlow<MediaState>

    /** Caller side: gather-complete, return the offer. */
    suspend fun createOffer(): SessionDesc

    /** Callee side: apply the remote offer, gather, return the answer. */
    suspend fun acceptOffer(remote: SessionDesc): SessionDesc

    /** Caller side: apply the remote answer; media starts connecting. */
    suspend fun setAnswer(remote: SessionDesc)

    fun setMuted(muted: Boolean)

    fun close()
}

/** Per-call engine factory the platform entry point injects into App. */
fun interface CallEngineProvider {
    /** A fresh engine for one call, configured with the ship's
     *  advertised ICE servers (empty = Tier 0, host candidates only). */
    fun create(iceServers: List<IceServer>): CallEngine
}

/** Fallback for platforms without a media stack — the UI is gated by
 *  [io.nisfeb.talon.ui.isCallsSupported], so this only exists to keep
 *  wiring total. */
object NoopCallEngine : CallEngine {
    override val state: StateFlow<MediaState> = MutableStateFlow(MediaState.Failed)
    override suspend fun createOffer(): SessionDesc = error("calls unsupported")
    override suspend fun acceptOffer(remote: SessionDesc): SessionDesc =
        error("calls unsupported")
    override suspend fun setAnswer(remote: SessionDesc) = error("calls unsupported")
    override fun setMuted(muted: Boolean) {}
    override fun close() {}
}

/** Extract the DTLS fingerprint from an SDP blob (`a=fingerprint:` line).
 *  Kept here — pure string work — so every platform pins identically. */
fun sdpFingerprint(sdp: String): String =
    sdp.lineSequence()
        .firstOrNull { it.startsWith("a=fingerprint:") }
        ?.removePrefix("a=fingerprint:")
        ?.trim()
        ?: ""
