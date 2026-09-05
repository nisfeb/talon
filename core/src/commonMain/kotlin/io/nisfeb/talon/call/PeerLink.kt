package io.nisfeb.talon.call

import kotlinx.coroutines.flow.StateFlow

/** One trickled ICE candidate, in the shape Galène's `ice` message wants. */
data class IceCandidate(
    val candidate: String,
    val sdpMid: String?,
    val sdpMLineIndex: Int,
)

/**
 * One peer connection in an SFU session.
 *
 * Party lines are a star: each participant holds one *up* link (their
 * mic, offered by us) plus one *down* link per other speaker (offered
 * by the server). Both directions trickle ICE, because the far end is
 * a server on a public address — the round trips are cheap, unlike the
 * ames control plane that forced vanilla ICE for 1:1 calls.
 *
 * ponytail: [CallEngine] is the 1:1 (non-trickle, sendrecv) primitive
 * and overlaps this by ~60% per platform. Folding CallEngine onto
 * PeerLink is the obvious cleanup, deliberately deferred until the
 * party-line path has shipped — the 1:1 path is in an RC under test
 * and its E2E is the regression net for that refactor.
 */
interface PeerLink {
    val state: StateFlow<MediaState>

    /** Local candidates as they are gathered. Set before offering. */
    fun onLocalCandidate(callback: (IceCandidate) -> Unit)

    /** Up link: create the offer for our outgoing audio. */
    suspend fun offer(): String

    /** Down link: apply the server's offer, return our answer. */
    suspend fun answerTo(remoteSdp: String): String

    /** Up link: apply the server's answer. */
    suspend fun applyAnswer(remoteSdp: String)

    fun addRemoteCandidate(candidate: IceCandidate)

    fun setMuted(muted: Boolean)

    /**
     * How loud this link's remote audio is right now, 0..1, or null
     * where the platform can't say. Polled rather than pushed —
     * WebRTC reports it per-packet, and the UI only needs "is this
     * person talking", at a rate a person can perceive.
     */
    fun audioLevel(): Float? = null

    /**
     * How loud *our own* microphone is right now, 0..1, or null where
     * the platform can't say.
     *
     * Read from the outgoing side rather than the incoming one, so it
     * works on the up link, which has no receivers at all. Worth
     * showing: without it there is no way to tell "nobody is talking"
     * from "my microphone is dead", and a muted mic looks identical to
     * a working one.
     */
    fun localAudioLevel(): Float? = null

    /**
     * Tap this link's decoded PCM for call recording (wire 7), or null
     * to stop. The callback gets 16-bit little-endian mono frames and
     * the sample rate. On a down link it is the remote speaker's audio;
     * on the up link it is our own microphone.
     *
     * Default no-op: a platform whose media stack can't be tapped does
     * not record, and [io.nisfeb.talon.ui.isCallRecordingSupported]
     * gates the UI so the feature is hidden rather than silently empty.
     */
    fun onPcm(sink: ((pcm: ByteArray, sampleRate: Int) -> Unit)?) {}

    fun close()
}

/** Per-link factory; the platform entry point injects the real one. */
fun interface PeerLinkFactory {
    /**
     * @param sendAudio true for the up link (captures the mic);
     *   false for down links, which only receive.
     */
    fun create(iceServers: List<IceServer>, sendAudio: Boolean): PeerLink
}
