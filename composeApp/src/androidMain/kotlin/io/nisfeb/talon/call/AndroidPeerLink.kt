package io.nisfeb.talon.call

import android.content.Context
import android.media.AudioManager
import io.nisfeb.talon.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate as WebRtcIceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/**
 * Android [PeerLink] over libwebrtc: one SFU stream, trickling ICE.
 * Up links add the mic and send only; down links receive only.
 */
class AndroidPeerLink(
    appContext: Context,
    iceServers: List<IceServer>,
    sendAudio: Boolean,
) : PeerLink {

    private val _state = MutableStateFlow(MediaState.Idle)
    override val state: StateFlow<MediaState> = _state

    private var micTrack: AudioTrack? = null
    private var onCandidate: ((IceCandidate) -> Unit)? = null
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val priorAudioMode = audioManager.mode

    private val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: WebRtcIceCandidate?) {
            val c = candidate ?: return
            onCandidate?.invoke(IceCandidate(c.sdp, c.sdpMid, c.sdpMLineIndex))
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> _state.value = MediaState.Live
                PeerConnection.PeerConnectionState.FAILED -> _state.value = MediaState.Failed
                PeerConnection.PeerConnectionState.CONNECTING ->
                    _state.value = MediaState.Connecting
                PeerConnection.PeerConnectionState.CLOSED -> _state.value = MediaState.Closed
                else -> {}
            }
        }

        override fun onIceCandidatesRemoved(candidates: Array<out WebRtcIceCandidate>?) {}
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(channel: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
    }

    private val factory: PeerConnectionFactory = WebRtcFactory.get(appContext)

    private val pc: PeerConnection = run {
        val servers = iceServers.map { s ->
            PeerConnection.IceServer.builder(s.url)
                .apply {
                    if (s.user.isNotEmpty()) {
                        setUsername(s.user)
                        setPassword(s.cred)
                    }
                }
                .createIceServer()
        }
        val config = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        factory.createPeerConnection(config, observer) ?: error("createPeerConnection failed")
    }

    init {
        if (sendAudio) {
            val source = factory.createAudioSource(MediaConstraints())
            val track = factory.createAudioTrack("talon-mic", source)
            // Galène requires one-directional streams: the offerer sends.
            pc.addTransceiver(
                track,
                RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.SEND_ONLY,
                ),
            )
            micTrack = track
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        }
    }

    override fun onLocalCandidate(callback: (IceCandidate) -> Unit) {
        onCandidate = callback
    }

    override suspend fun offer(): String {
        val offer = suspendSdp { pc.createOffer(it, MediaConstraints()) }
        suspendSet { pc.setLocalDescription(it, offer) }
        _state.value = MediaState.Connecting
        return offer.description
    }

    override suspend fun answerTo(remoteSdp: String): String {
        suspendSet {
            pc.setRemoteDescription(it, SessionDescription(SessionDescription.Type.OFFER, remoteSdp))
        }
        val answer = suspendSdp { pc.createAnswer(it, MediaConstraints()) }
        suspendSet { pc.setLocalDescription(it, answer) }
        _state.value = MediaState.Connecting
        return answer.description
    }

    override suspend fun applyAnswer(remoteSdp: String) {
        suspendSet {
            pc.setRemoteDescription(
                it,
                SessionDescription(SessionDescription.Type.ANSWER, remoteSdp),
            )
        }
    }

    override fun addRemoteCandidate(candidate: IceCandidate) {
        runCatching {
            pc.addIceCandidate(
                WebRtcIceCandidate(
                    candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate,
                ),
            )
        }.onFailure { Log.w("PartyLine", "addIceCandidate failed", it) }
    }

    override fun setMuted(muted: Boolean) {
        micTrack?.setEnabled(!muted)
    }

    // Android's RtpReceiver has no getSynchronizationSources, so the
    // level comes from inbound-rtp stats instead. getStats is async,
    // so a poll refreshes this and audioLevel() reads what it last
    // saw — the caller polls at 250ms anyway.
    @Volatile private var lastLevel: Float? = null
    @Volatile private var statsInFlight = false

    override fun audioLevel(): Float? {
        if (!statsInFlight && !closed.value) {
            statsInFlight = true
            runCatching {
                pc.getStats { report ->
                    var level: Float? = null
                    for (stat in report.statsMap.values) {
                        if (stat.type == "inbound-rtp") {
                            (stat.members["audioLevel"] as? Number)?.let {
                                level = it.toFloat()
                            }
                        }
                    }
                    lastLevel = level
                    statsInFlight = false
                }
            }.onFailure { statsInFlight = false }
        }
        return lastLevel
    }

    private val closed = kotlinx.atomicfu.atomic(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { micTrack?.setEnabled(false) }
        // The factory is process-wide; see WebRtcFactory.
        runCatching { pc.close() }
        if (micTrack != null) audioManager.mode = priorAudioMode
        _state.value = MediaState.Closed
    }

    private suspend fun suspendSdp(call: (SdpObserver) -> Unit): SessionDescription {
        val d = CompletableDeferred<SessionDescription>()
        call(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) { d.complete(desc) }
            override fun onCreateFailure(error: String?) {
                d.completeExceptionally(IllegalStateException(error ?: "sdp failure"))
            }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        })
        return d.await()
    }

    private suspend fun suspendSet(call: (SdpObserver) -> Unit) {
        val d = CompletableDeferred<Unit>()
        call(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {}
            override fun onSetSuccess() { d.complete(Unit) }
            override fun onSetFailure(error: String?) {
                d.completeExceptionally(IllegalStateException(error ?: "set failure"))
            }
        })
        d.await()
    }

}
