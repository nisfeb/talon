package io.nisfeb.talon.call

import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCAnswerOptions
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCIceGatheringState
import dev.onvoid.webrtc.RTCOfferOptions
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCPeerConnectionState
import dev.onvoid.webrtc.RTCSdpType
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import dev.onvoid.webrtc.media.audio.AudioOptions
import dev.onvoid.webrtc.media.audio.AudioTrack
import io.nisfeb.talon.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout

/**
 * Desktop CallEngine over webrtc-java (JNI libwebrtc). Audio-only,
 * Tier 0 (host candidates, no ICE servers) — the v0 spike surface.
 * Remote audio renders automatically through the default device via
 * libwebrtc's audio device module; mic capture likewise.
 */
class DesktopCallEngine : CallEngine {

    private val _state = MutableStateFlow(MediaState.Idle)
    override val state: StateFlow<MediaState> = _state

    private val factory = PeerConnectionFactory()
    private val gathered = CompletableDeferred<Unit>()
    private var micTrack: AudioTrack? = null

    private val pc: RTCPeerConnection = factory.createPeerConnection(
        RTCConfiguration(), // no ICE servers: Tier 0 only for the spike
        object : PeerConnectionObserver {
            override fun onIceCandidate(candidate: RTCIceCandidate?) {
                // Non-trickle: candidates accumulate into the local SDP.
                Log.i("Trunk", "ice candidate: ${candidate?.sdp}")
            }

            override fun onIceGatheringChange(gatherState: RTCIceGatheringState?) {
                if (gatherState == RTCIceGatheringState.COMPLETE) gathered.complete(Unit)
            }

            override fun onIceConnectionChange(iceState: dev.onvoid.webrtc.RTCIceConnectionState?) {
                Log.i("Trunk", "ice state: $iceState")
            }

            override fun onConnectionChange(pcState: RTCPeerConnectionState?) {
                Log.i("Trunk", "pc state: $pcState")
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
        val source = factory.createAudioSource(AudioOptions())
        val track = factory.createAudioTrack("talon-mic", source)
        pc.addTrack(track, listOf("talon-call"))
        micTrack = track
    }

    override suspend fun createOffer(): SessionDesc {
        _state.value = MediaState.Gathering
        val offer = suspendSdp { pc.createOffer(RTCOfferOptions(), it) }
        suspendSet { pc.setLocalDescription(offer, it) }
        awaitGathering()
        val sdp = pc.localDescription?.sdp ?: error("no local description")
        Log.i("Trunk", "offer sdp: " + sdp.lines().count { it.startsWith("a=candidate") } + " candidates")
        return SessionDesc(sdp, sdpFingerprint(sdp))
    }

    override suspend fun acceptOffer(remote: SessionDesc): SessionDesc {
        _state.value = MediaState.Gathering
        val desc = RTCSessionDescription(RTCSdpType.OFFER, remote.sdp)
        suspendSet { pc.setRemoteDescription(desc, it) }
        val answer = suspendSdp { pc.createAnswer(RTCAnswerOptions(), it) }
        suspendSet { pc.setLocalDescription(answer, it) }
        awaitGathering()
        val sdp = pc.localDescription?.sdp ?: error("no local description")
        Log.i("Trunk", "answer sdp: " + sdp.lines().count { it.startsWith("a=candidate") } + " candidates")
        // No manual state write: the pc observer owns transitions. A
        // fast connect can land CONNECTED before this returns, and a
        // blind Connecting here would stomp Live (loopback bisect bug).
        return SessionDesc(sdp, sdpFingerprint(sdp))
    }

    override suspend fun setAnswer(remote: SessionDesc) {
        val desc = RTCSessionDescription(RTCSdpType.ANSWER, remote.sdp)
        suspendSet { pc.setRemoteDescription(desc, it) }
    }

    override fun setMuted(muted: Boolean) {
        micTrack?.isEnabled = !muted
    }

    override fun close() {
        runCatching { pc.close() }
        runCatching { factory.dispose() }
        _state.value = MediaState.Closed
    }

    /** LAN gathering completes in well under a second; the timeout only
     *  guards against an ADM/network stack wedge. */
    private suspend fun awaitGathering() = withTimeout(10_000) { gathered.await() }

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

val DesktopCallEngineProvider: CallEngineProvider = CallEngineProvider { DesktopCallEngine() }
