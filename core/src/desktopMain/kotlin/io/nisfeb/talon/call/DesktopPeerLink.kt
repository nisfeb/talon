package io.nisfeb.talon.call

import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCAnswerOptions
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCIceServer
import dev.onvoid.webrtc.RTCOfferOptions
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCPeerConnectionState
import dev.onvoid.webrtc.RTCRtpTransceiverDirection
import dev.onvoid.webrtc.RTCSdpType
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import dev.onvoid.webrtc.media.MediaStreamTrack
import dev.onvoid.webrtc.media.audio.AudioOptions
import dev.onvoid.webrtc.media.audio.AudioTrack
import io.nisfeb.talon.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Desktop [PeerLink] over webrtc-java: one SFU stream, trickling ICE.
 * Up links add the mic and send only; down links receive only.
 */
class DesktopPeerLink(
    iceServers: List<IceServer>,
    private val sendAudio: Boolean,
    /**
     * Where the outgoing audio comes from, instead of a microphone.
     *
     * Null captures a sound card, which is what the app wants. A
     * headless process passes a CustomAudioSource and pushes PCM into
     * it on its own clock — that is the only injection point that
     * works: an AudioDeviceModule's setAudioSink / setAudioSource
     * stop firing once a PeerConnectionFactory owns the module, so a
     * HeadlessAudioDeviceModule is good for *not* opening a sound
     * card and nothing more.
     */
    private val micPcm: dev.onvoid.webrtc.media.audio.CustomAudioSource? = null,
    /** Where the incoming audio goes, instead of a speaker. */
    private val remotePcm: dev.onvoid.webrtc.media.audio.AudioTrackSink? = null,
) : PeerLink {

    private val _state = MutableStateFlow(MediaState.Idle)
    override val state: StateFlow<MediaState> = _state

    private val factory = DesktopWebRtcFactory.get()
    private var micTrack: AudioTrack? = null
    private var onCandidate: ((IceCandidate) -> Unit)? = null

    private val config = RTCConfiguration().apply {
        for (s in iceServers) {
            val server = RTCIceServer()
            server.urls.add(s.url)
            if (s.user.isNotEmpty()) {
                server.username = s.user
                server.password = s.cred
            }
            this.iceServers.add(server)
        }
    }

    private val pc: RTCPeerConnection = factory.createPeerConnection(
        config,
        object : PeerConnectionObserver {
            override fun onIceCandidate(candidate: RTCIceCandidate?) {
                val c = candidate ?: return
                onCandidate?.invoke(
                    IceCandidate(c.sdp, c.sdpMid, c.sdpMLineIndex),
                )
            }

            override fun onTrack(transceiver: dev.onvoid.webrtc.RTCRtpTransceiver?) {
                val sink = remotePcm ?: return
                val track = transceiver?.receiver?.track as? AudioTrack ?: return
                runCatching { track.addSink(sink) }
                    .onFailure { Log.w("PartyLine", "could not tap the remote track", it) }
            }

            override fun onConnectionChange(pcState: RTCPeerConnectionState?) {
                when (pcState) {
                    RTCPeerConnectionState.CONNECTED -> _state.value = MediaState.Live
                    RTCPeerConnectionState.FAILED -> _state.value = MediaState.Failed
                    RTCPeerConnectionState.CONNECTING -> _state.value = MediaState.Connecting
                    RTCPeerConnectionState.CLOSED -> _state.value = MediaState.Closed
                    else -> {}
                }
            }
        },
    )

    init {
        if (sendAudio) {
            val source = micPcm ?: factory.createAudioSource(AudioOptions())
            val track = factory.createAudioTrack("talon-mic", source)
            // Galène requires every stream to be one-directional: the
            // offerer is always the sender.
            pc.addTransceiver(track, dev.onvoid.webrtc.RTCRtpTransceiverInit().apply {
                direction = RTCRtpTransceiverDirection.SEND_ONLY
            })
            micTrack = track
        }
    }

    override fun onLocalCandidate(callback: (IceCandidate) -> Unit) {
        onCandidate = callback
    }

    override suspend fun offer(): String {
        val offer = suspendSdp { pc.createOffer(RTCOfferOptions(), it) }
        suspendSet { pc.setLocalDescription(offer, it) }
        _state.value = MediaState.Connecting
        return offer.sdp
    }

    override suspend fun answerTo(remoteSdp: String): String {
        suspendSet { pc.setRemoteDescription(RTCSessionDescription(RTCSdpType.OFFER, remoteSdp), it) }
        val answer = suspendSdp { pc.createAnswer(RTCAnswerOptions(), it) }
        suspendSet { pc.setLocalDescription(answer, it) }
        _state.value = MediaState.Connecting
        return answer.sdp
    }

    override suspend fun applyAnswer(remoteSdp: String) {
        suspendSet {
            pc.setRemoteDescription(RTCSessionDescription(RTCSdpType.ANSWER, remoteSdp), it)
        }
    }

    override fun addRemoteCandidate(candidate: IceCandidate) {
        runCatching {
            pc.addIceCandidate(
                RTCIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate),
            )
        }.onFailure { Log.w("PartyLine", "addIceCandidate failed", it) }
    }

    override fun setMuted(muted: Boolean) {
        micTrack?.isEnabled = !muted
    }

    /**
     * Remote audio level, or null when it can't be read.
     *
     * Two sources, because the cheap one is not always there.
     * getSynchronizationSources reports the level carried in RTP's
     * own header extension — free, but only populated when that
     * extension was negotiated. Galène's down streams did not carry
     * it, so desktop showed nobody speaking while Android, which
     * reads inbound-rtp statistics instead, showed everyone.
     *
     * Fall back to the same statistic Android uses. It is
     * asynchronous, so a call kicks off a refresh and returns what
     * the last one saw; the caller polls at 4Hz anyway.
     */
    override fun audioLevel(): Float? =
        runCatching {
            pc.receivers.firstNotNullOfOrNull { r ->
                // > 0, not just non-null. audioLevel is a primitive
                // double, so an existing source whose header extension
                // was never negotiated reports 0.0 — which is not null,
                // so the fallback below never ran and desktop reported
                // silence forever. Nobody is audible at exactly zero
                // anyway, so losing that value costs nothing.
                r.synchronizationSources?.firstOrNull()?.audioLevel?.toFloat()
                    ?.takeIf { it > 0f }
            }
        }.getOrNull() ?: statsAudioLevel()

    /**
     * Our own microphone level, from the MEDIA_SOURCE statistic.
     *
     * Not inbound-rtp: the up link has no receivers, and outbound-rtp
     * describes what was sent rather than what the microphone heard —
     * which is silence-suppressed, so a quiet talker reads as nothing.
     */
    override fun localAudioLevel(): Float? = statsAudioLevel(local = true)

    private val lastStatsLevel = kotlinx.atomicfu.atomic(-1f)
    private val lastLocalLevel = kotlinx.atomicfu.atomic(-1f)
    private val statsInFlight = kotlinx.atomicfu.atomic(false)
    private val localInFlight = kotlinx.atomicfu.atomic(false)

    private fun statsAudioLevel(local: Boolean = false): Float? {
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
                    inFlight.value = false
                }
            }.onFailure { inFlight.value = false }
        }
        return last.value.takeIf { it >= 0f }
    }

    private val closed = kotlinx.atomicfu.atomic(false)

    override fun close() {
        // Idempotent, and never disposes the factory: it is
        // process-wide, and tearing it down while ICE gathering is
        // still running on a native thread is a use-after-free.
        if (!closed.compareAndSet(false, true)) return
        runCatching { micTrack?.isEnabled = false }
        runCatching { pc.close() }
        _state.value = MediaState.Closed
    }

    private suspend fun suspendSdp(
        call: (CreateSessionDescriptionObserver) -> Unit,
    ): RTCSessionDescription {
        val d = CompletableDeferred<RTCSessionDescription>()
        call(object : CreateSessionDescriptionObserver {
            override fun onSuccess(desc: RTCSessionDescription) { d.complete(desc) }
            override fun onFailure(error: String?) {
                d.completeExceptionally(IllegalStateException(error ?: "sdp failure"))
            }
        })
        return d.await()
    }

    private suspend fun suspendSet(call: (SetSessionDescriptionObserver) -> Unit) {
        val d = CompletableDeferred<Unit>()
        call(object : SetSessionDescriptionObserver {
            override fun onSuccess() { d.complete(Unit) }
            override fun onFailure(error: String?) {
                d.completeExceptionally(IllegalStateException(error ?: "set failure"))
            }
        })
        d.await()
    }
}

val DesktopPeerLinkFactory: PeerLinkFactory =
    PeerLinkFactory { ice, sendAudio -> DesktopPeerLink(ice, sendAudio) }
