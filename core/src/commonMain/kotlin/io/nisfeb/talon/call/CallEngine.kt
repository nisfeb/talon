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
 * Who is showing a camera on a 1:1 call.
 *
 * Both sides start false: a call is audio until someone turns their
 * camera on, which is the behaviour a voice-first client should have
 * and also what keeps the camera indicator dark on an ordinary call.
 */
data class VideoState(
    val localOn: Boolean = false,
    val remoteOn: Boolean = false,
) {
    val anyOn: Boolean get() = localOn || remoteOn
}

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

    /**
     * Who is showing a camera, ours and theirs.
     *
     * The renderer watches this rather than polling: a remote camera
     * can start at any point in a call, and the surface has to appear
     * when it does.
     */
    val video: StateFlow<VideoState>

    /**
     * Open (or close) our camera and send it.
     *
     * Deliberately free of renegotiation. Every engine negotiates a
     * sendrecv video transceiver in its very first offer, with no
     * track attached; enabling swaps a camera track onto that sender,
     * which WebRTC allows without a new offer/answer round. So trunk
     * never learns what video is, CallController gains no protocol,
     * and an old peer that answered with a rejected m-line simply
     * never shows a picture.
     *
     * The camera is not opened until this is called, so an audio call
     * leaves the indicator light off and never prompts.
     *
     * Returns false when the camera could not be opened — permission
     * refused, no device, already in use — so the caller can say so
     * rather than leaving a button that appears to do nothing.
     */
    suspend fun setCameraEnabled(enabled: Boolean): Boolean

    fun close()
}

/** Per-call engine factory the platform entry point injects into App. */
fun interface CallEngineProvider {
    /** A fresh engine for one call, configured with the ship's
     *  advertised ICE servers (empty = Tier 0, host candidates only). */
    fun create(iceServers: List<IceServer>): CallEngine
}

/**
 * Fallback for platforms without a media stack — the UI is gated by
 * [io.nisfeb.talon.ui.isCallsSupported], so this mostly exists to keep
 * wiring total. [why] surfaces to the user as the reason the call
 * ended, so a platform that stands one up in place of a real engine
 * (Android, while the mic permission is still unanswered) should say
 * something the user can act on.
 */
class UnavailableCallEngine(private val why: String) : CallEngine {
    override val state: StateFlow<MediaState> = MutableStateFlow(MediaState.Failed)
    override suspend fun createOffer(): SessionDesc = error(why)
    override suspend fun acceptOffer(remote: SessionDesc): SessionDesc = error(why)
    override suspend fun setAnswer(remote: SessionDesc) = error(why)
    override fun setMuted(muted: Boolean) {}
    override val video: StateFlow<VideoState> = MutableStateFlow(VideoState())
    override suspend fun setCameraEnabled(enabled: Boolean) = false
    override fun close() {}
}

val NoopCallEngine: CallEngine = UnavailableCallEngine("calls aren't available here")

/** Extract the DTLS fingerprint from an SDP blob (`a=fingerprint:` line).
 *  Kept here — pure string work — so every platform pins identically. */
fun sdpFingerprint(sdp: String): String =
    sdp.lineSequence()
        .firstOrNull { it.startsWith("a=fingerprint:") }
        ?.removePrefix("a=fingerprint:")
        ?.trim()
        ?: ""
