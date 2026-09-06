package io.nisfeb.talon.call

import android.content.Context
import io.nisfeb.talon.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    /** The sendrecv video transceiver, created up-front with no track,
     *  and the sender it carries. The transceiver is kept as well
     *  because only it knows whether the m-line ended up negotiated in
     *  a direction that sends — an open camera is not a send path. */
    private var videoTransceiver: RtpTransceiver? = null
    private var videoSender: RtpSender? = null
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastRemoteFrameMs = java.util.concurrent.atomic.AtomicLong(0)
    private var remoteWatch: Job? = null
    private var remoteSink: org.webrtc.VideoSink? = null
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
            // Not remoteOn = true: the video m-line is pre-negotiated on
            // every call, so this fires for audio-only ones too — a black
            // pane that never went away when the peer closed their camera.
            // Frames are the only honest signal.
            watchRemoteVideo(track)
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
        videoTransceiver = runCatching {
            pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.SEND_RECV,
                ),
            )
        }.onFailure { Log.w(TAG, "no video transceiver; call stays audio-only", it) }
            .getOrNull()
        videoSender = videoTransceiver?.sender
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
        adoptVideoTransceiver()
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
        if (!canSendVideo()) {
            // Opening it anyway would light the indicator, show a
            // preview and set localOn while the far end saw nothing.
            Log.w(TAG, "the peer negotiated no video send path")
            return false
        }
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

    /**
     * Whether the negotiated video m-line actually carries our frames.
     *
     * A peer can answer recvonly, or reject the m-line outright and
     * leave the transceiver with no mid at all; the sender then sends
     * nowhere. Every read is caught because a closed call disposes the
     * transceiver, and a disposed one throws rather than answering —
     * which is the same answer anyway.
     */
    private fun canSendVideo(): Boolean = runCatching {
        val tx = videoTransceiver ?: return@runCatching false
        if (tx.mid.isNullOrEmpty()) return@runCatching false
        // Null until the answer lands, and our own direction is
        // sendrecv, so an offer still in flight is not a refusal.
        val dir = tx.currentDirection ?: return@runCatching true
        dir == RtpTransceiver.RtpTransceiverDirection.SEND_RECV ||
            dir == RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
    }.getOrDefault(false)

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

    /**
     * Re-point [videoTransceiver] and [videoSender] at the transceiver
     * the offer negotiated.
     *
     * The constructor's addTransceiver only associates on the offering
     * side, so when answering our sender belonged to a second,
     * unassociated transceiver no m-line pointed at — the callee could
     * never send video, only receive it.
     */
    private fun adoptVideoTransceiver() {
        runCatching {
            val videos = pc.transceivers.orEmpty().filter {
                it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
            }
            // The one with a mid is the one bound to the offer's m-line
            // and the only one the answer is generated from.
            val target = videos.firstOrNull { !it.mid.isNullOrEmpty() }
                ?: videos.firstOrNull()
                ?: return@runCatching
            target.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
            videoTransceiver = target
            videoSender = target.sender
            // Carry over a camera that is already running.
            localVideo?.let { cam -> runCatching { target.sender.setTrack(cam, false) } }
        }.onFailure { Log.w(TAG, "could not adopt the video transceiver", it) }
    }

    /** Drive remoteOn from arriving frames: a disabled sender stops
     *  sending, so "frames recently" is exactly "their camera is on". */
    private fun watchRemoteVideo(track: VideoTrack) {
        val sink = org.webrtc.VideoSink { lastRemoteFrameMs.set(System.currentTimeMillis()) }
        remoteSink = sink
        runCatching { track.addSink(sink) }
        remoteWatch?.cancel()
        remoteWatch = engineScope.launch {
            while (true) {
                val on = System.currentTimeMillis() - lastRemoteFrameMs.get() < REMOTE_VIDEO_IDLE_MS
                if (_video.value.remoteOn != on) {
                    _video.value = _video.value.copy(remoteOn = on)
                }
                delay(REMOTE_VIDEO_POLL_MS)
            }
        }
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
        remoteWatch?.cancel()
        remoteWatch = null
        remoteSink?.let { s -> runCatching { remoteVideoTrack?.removeSink(s) } }
        remoteSink = null
        engineScope.cancel()
        remoteVideoTrack = null
        _video.value = VideoState()
        // Close, don't dispose the factory: it is process-wide, and
        // tearing it down while ICE gathering is still running on a
        // native thread crashes the app seconds later, once a callback
        // fires into freed memory.
        runCatching { pc.close() }
        // See AndroidPeerLink.close: close() releases nothing; dispose()
        // frees the PC's senders/receivers and the observer's JNI ref.
        runCatching { pc.dispose() }
        runCatching { micTrack?.dispose() }
        micTrack = null
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

        /** No frame for this long means their camera is off. */
        private const val REMOTE_VIDEO_IDLE_MS = 1_500L
        private const val REMOTE_VIDEO_POLL_MS = 400L
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
     * Self-mic capture for call recording (wire 7).
     *
     * The audio device module's record-samples callback is process-
     * global (16-bit PCM as the mic hears it, before encode). The
     * recording up link sets this while active and clears it after, so
     * it is null — and free — the rest of the time. Only one up link
     * captures a mic at once, so a single sink is enough.
     *
     * The tap is pre-APM: these are the AudioRecord buffers as they
     * arrive, before the native echo canceller, noise suppressor and
     * gain control touch them. So in a recording our own voice sits
     * well below the remote clips, and another speaker in the room
     * bleeds into our leg of it. libwebrtc's Android API offers no
     * post-APM capture point, so this is the only mic tap there is.
     */
    @Volatile
    var micSink: ((pcm: ByteArray, sampleRate: Int) -> Unit)? = null

    /** Downmix N-channel 16-bit LE PCM to mono. WebRtcAudioRecord builds
     *  a fresh array per 10ms frame, so unlike the down link's reused
     *  ByteBuffer there is nothing to defend against: the mic is normally
     *  mono and that path hands the buffer straight on. */
    private fun toMono(data: ByteArray, channels: Int): ByteArray {
        if (channels <= 1) return data
        val frames = data.size / (channels * 2)
        val out = ByteArray(frames * 2)
        for (f in 0 until frames) {
            var acc = 0
            for (c in 0 until channels) {
                val b = (f * channels + c) * 2
                acc += (data[b + 1].toInt() shl 8) or (data[b].toInt() and 0xFF)
            }
            val v = acc / channels
            out[f * 2] = (v and 0xFF).toByte()
            out[f * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

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
                JavaAudioDeviceModule.builder(app)
                    // Tap the recorded mic for call recording. Fires
                    // continuously while the mic is live; forwards only
                    // when a recording has set [micSink].
                    .setSamplesReadyCallback { samples ->
                        val sink = micSink ?: return@setSamplesReadyCallback
                        runCatching {
                            sink(toMono(samples.data, samples.channelCount), samples.sampleRate)
                        }
                    }
                    .createAudioDeviceModule(),
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
