package io.nisfeb.talon.call

import android.content.Context
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
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.MediaStreamTrack
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Android CallEngine over libwebrtc (getstream build). Mirrors
 * DesktopCallEngine: audio-only, non-trickle (gather-complete SDPs).
 * Audio mode and focus go through [CallAudioSession] (default
 * routing — earpiece on handsets), refcounted so an overlapping
 * party line can't restore the wrong mode under us.
 * ponytail: no speakerphone/proximity handling yet — default routing
 * only; add an audio-route control when real-device testing demands it.
 */
class AndroidCallEngine(
    // A `val` because the camera is opened long after construction:
    // Camera2Enumerator and capturer.initialize both need a Context.
    private val appContext: Context,
    configuredIce: List<IceServer>,
) : CallEngine {

    private val _state = MutableStateFlow(MediaState.Idle)
    override val state: StateFlow<MediaState> = _state

    private val gathered = CompletableDeferred<Unit>()
    private var micTrack: AudioTrack? = null

    // ── video ────────────────────────────────────────────────────
    private val _video = MutableStateFlow(VideoState())
    override val video: StateFlow<VideoState> = _video

    /** The sendrecv video sender, created up-front with no track. */
    private var videoSender: RtpSender? = null
    private var capturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var localVideo: VideoTrack? = null

    /** Local and remote camera tracks, for [VideoSurface] to render.
     *  Android-only members: the renderer needs libwebrtc's own track
     *  object, which cannot cross into commonMain. */
    val localVideoTrack: VideoTrack? get() = localVideo
    @Volatile var remoteVideoTrack: VideoTrack? = null
        private set

    private val observer = object : PeerConnection.Observer {
        override fun onTrack(transceiver: RtpTransceiver?) {
            // The far end turned a camera on (or had one from the
            // start). Unified Plan fires this once per transceiver.
            val track = transceiver?.receiver?.track() as? VideoTrack ?: return
            remoteVideoTrack = track
            _video.value = _video.value.copy(remoteOn = true)
            Log.i(TAG, "remote video track ${track.id()}")
        }

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
        CallAudioSession.acquire(appContext)
        // A sendrecv video transceiver in the very first offer, with no
        // track on it. Attaching a camera later is then a sender
        // setTrack, which needs no renegotiation — so trunk never
        // learns what video is. The cost is one extra m-line on every
        // call, which an audio-only peer simply rejects.
        videoSender = runCatching {
            pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.SEND_RECV,
                ),
            ).sender
        }.onFailure { Log.w(TAG, "no video transceiver; call stays audio-only", it) }
            .getOrNull()
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

    /**
     * Open the camera and hang it on the pre-negotiated sender, or take
     * it back off.
     *
     * Front camera by preference — this is a chat client, not a
     * documentary tool. 640x480 at 30fps: high enough to read a face,
     * low enough that a phone on cellular does not melt, and Talon has
     * no simulcast to fall back on.
     */
    override suspend fun setCameraEnabled(enabled: Boolean): Boolean {
        val sender = videoSender ?: return false
        if (!enabled) {
            runCatching { capturer?.stopCapture() }
            runCatching { sender.setTrack(null, false) }
            releaseCamera()
            _video.value = _video.value.copy(localOn = false)
            return true
        }
        if (localVideo != null) return true
        return runCatching {
            val enumerator = Camera2Enumerator(appContext)
            val names = enumerator.deviceNames
            val name = names.firstOrNull { enumerator.isFrontFacing(it) }
                ?: names.firstOrNull()
                ?: error("no camera on this device")
            val cap = enumerator.createCapturer(name, null)
                ?: error("could not open camera $name")
            val helper = SurfaceTextureHelper.create(
                "talon-capture", WebRtcFactory.eglBase.eglBaseContext,
            )
            val source = factory.createVideoSource(false)
            cap.initialize(helper, appContext, source.capturerObserver)
            cap.startCapture(640, 480, 30)
            val track = factory.createVideoTrack("talon-cam", source)
            sender.setTrack(track, false)
            capturer = cap
            surfaceHelper = helper
            videoSource = source
            localVideo = track
            _video.value = _video.value.copy(localOn = true)
            true
        }.getOrElse {
            // A refused permission and a camera already in use look the
            // same from here; both mean "no picture", and the caller
            // says so rather than leaving a dead button.
            Log.w(TAG, "could not start the camera", it)
            releaseCamera()
            false
        }
    }

    private fun releaseCamera() {
        runCatching { capturer?.dispose() }
        runCatching { surfaceHelper?.dispose() }
        runCatching { videoSource?.dispose() }
        capturer = null
        surfaceHelper = null
        videoSource = null
        localVideo = null
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
        // Stop capture before the pc goes: a running capturer feeding a
        // closed source is a native callback into freed memory, the
        // same hazard as disposing the factory mid-gather.
        runCatching { capturer?.stopCapture() }
        releaseCamera()
        remoteVideoTrack = null
        _video.value = VideoState()
        // Close, don't dispose the factory: it is process-wide, and
        // tearing it down while ICE gathering is still running on a
        // native thread crashes the app seconds later, once a callback
        // fires into freed memory.
        runCatching { pc.close() }
        // Inside the closed-guard, so a double close can't double-
        // decrement the session refcount.
        CallAudioSession.release()
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

    private companion object {
        private const val TAG = "AndroidCallEngine"
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

    /**
     * One EGL context for the process.
     *
     * Hardware encode/decode and every SurfaceViewRenderer have to
     * share it — a renderer initialised against a different context
     * shows black. Created lazily so an audio-only install never
     * touches EGL at all.
     */
    val eglBase: EglBase by lazy { EglBase.create() }

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
            // Without these the factory has no video codecs at all and
            // a video m-line negotiates to nothing. Harmless for party
            // lines, which never attach a camera.
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true),
            )
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
            .also { factory = it }
    }
}
