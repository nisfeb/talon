package io.nisfeb.talon.call

import android.content.Context
import android.media.AudioManager
import io.nisfeb.talon.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Android CallEngine over libwebrtc (getstream build). Mirrors
 * DesktopCallEngine: audio-only, non-trickle (gather-complete SDPs).
 * Puts the device in MODE_IN_COMMUNICATION for the call (default
 * routing — earpiece on handsets) and restores the prior mode on
 * close.
 * ponytail: no speakerphone/proximity handling yet — default routing
 * only; add an audio-route control when real-device testing demands it.
 */
class AndroidCallEngine(
    appContext: Context,
    configuredIce: List<IceServer>,
) : CallEngine {

    private val _state = MutableStateFlow(MediaState.Idle)
    override val state: StateFlow<MediaState> = _state

    private val gathered = CompletableDeferred<Unit>()
    private var micTrack: AudioTrack? = null
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val priorAudioMode = audioManager.mode

    private val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate?) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onIceGatheringChange(gatherState: PeerConnection.IceGatheringState?) {
            if (gatherState == PeerConnection.IceGatheringState.COMPLETE) {
                gathered.complete(Unit)
            }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            Log.i("Trunk", "pc state: $newState")
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> _state.value = MediaState.Live
                PeerConnection.PeerConnectionState.FAILED -> _state.value = MediaState.Failed
                PeerConnection.PeerConnectionState.CONNECTING ->
                    _state.value = MediaState.Connecting
                PeerConnection.PeerConnectionState.CLOSED -> _state.value = MediaState.Closed
                else -> {}
            }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(channel: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
    }

    private val factory: PeerConnectionFactory = WebRtcFactory.get(appContext)

    private val pc: PeerConnection = run {
        val servers = configuredIce.map { s ->
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
        val source = factory.createAudioSource(MediaConstraints())
        val track = factory.createAudioTrack("talon-mic", source)
        pc.addTrack(track, listOf("talon-call"))
        micTrack = track
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
    }

    override suspend fun createOffer(): SessionDesc {
        _state.value = MediaState.Gathering
        val offer = suspendSdp { pc.createOffer(it, MediaConstraints()) }
        suspendSet { pc.setLocalDescription(it, offer) }
        awaitGathering()
        val sdp = pc.localDescription?.description ?: error("no local description")
        return SessionDesc(sdp, sdpFingerprint(sdp))
    }

    override suspend fun acceptOffer(remote: SessionDesc): SessionDesc {
        _state.value = MediaState.Gathering
        val desc = SessionDescription(SessionDescription.Type.OFFER, remote.sdp)
        suspendSet { pc.setRemoteDescription(it, desc) }
        val answer = suspendSdp { pc.createAnswer(it, MediaConstraints()) }
        suspendSet { pc.setLocalDescription(it, answer) }
        awaitGathering()
        val sdp = pc.localDescription?.description ?: error("no local description")
        return SessionDesc(sdp, sdpFingerprint(sdp))
    }

    override suspend fun setAnswer(remote: SessionDesc) {
        val desc = SessionDescription(SessionDescription.Type.ANSWER, remote.sdp)
        suspendSet { pc.setRemoteDescription(it, desc) }
    }

    override fun setMuted(muted: Boolean) {
        micTrack?.setEnabled(!muted)
    }

    override fun close() {
        // Idempotent: a call can end down several paths at once (remote
        // hangup racing a local failure) and closing twice used to mean
        // a double native teardown.
        if (!closed.compareAndSet(false, true)) return
        runCatching { micTrack?.setEnabled(false) }
        // Close, don't dispose the factory: it is process-wide, and
        // tearing it down while ICE gathering is still running on a
        // native thread crashes the app seconds later, once a callback
        // fires into freed memory.
        runCatching { pc.close() }
        audioManager.mode = priorAudioMode
        _state.value = MediaState.Closed
    }

    private val closed = kotlinx.atomicfu.atomic(false)

    /** A dead STUN/TURN server must degrade, not break: when gathering
     *  stalls (unreachable server keeps ICE in %gathering), proceed
     *  with whatever candidates we have — Tier 0 still works. */
    private suspend fun awaitGathering() {
        runCatching { withTimeout(8_000) { gathered.await() } }
            .onFailure { Log.w("Trunk", "gathering incomplete after 8s — proceeding with partial candidates") }
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

/**
 * One [PeerConnectionFactory] for the process.
 *
 * libwebrtc's factory is expensive and designed to be shared; it also
 * owns the audio device module and native threads. Creating one per
 * call and disposing it on hang-up meant a teardown could race the ICE
 * gathering of the call that was ending — a use-after-free that
 * surfaced as a crash a few seconds after "busy". Individual
 * PeerConnections are still closed per call; the factory outlives them.
 */
internal object WebRtcFactory {
    private var factory: PeerConnectionFactory? = null

    @Synchronized
    fun get(context: Context): PeerConnectionFactory {
        factory?.let { return it }
        val app = context.applicationContext
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(app)
                .createInitializationOptions(),
        )
        return PeerConnectionFactory.builder()
            .setAudioDeviceModule(
                JavaAudioDeviceModule.builder(app).createAudioDeviceModule(),
            )
            .createPeerConnectionFactory()
            .also { factory = it }
    }
}
