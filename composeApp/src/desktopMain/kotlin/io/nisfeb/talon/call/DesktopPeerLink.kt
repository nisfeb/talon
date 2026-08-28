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
            val source = factory.createAudioSource(AudioOptions())
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

    override fun audioLevel(): Float? =
        runCatching {
            // getSynchronizationSources carries the level RTP already
            // sends in its header extension, so this costs nothing
            // extra on the wire and needs no stats round trip.
            pc.receivers.firstNotNullOfOrNull { r ->
                r.synchronizationSources?.firstOrNull()?.audioLevel?.toFloat()
            }
        }.getOrNull()

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
