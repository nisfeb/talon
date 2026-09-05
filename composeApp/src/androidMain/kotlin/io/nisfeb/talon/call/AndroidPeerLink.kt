package io.nisfeb.talon.call

import android.content.Context
import io.nisfeb.talon.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.IceCandidate as WebRtcIceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Android [PeerLink] over libwebrtc: one SFU stream, trickling ICE.
 * Up links add the mic and send only; down links receive only.
 */
class AndroidPeerLink(
    private val appContext: Context,
    iceServers: List<IceServer>,
    private val sendAudio: Boolean,
) : PeerLink {

    private val _state = MutableStateFlow(MediaState.Idle)
    override val state: StateFlow<MediaState> = _state

    private var micTrack: AudioTrack? = null
    private var onCandidate: ((IceCandidate) -> Unit)? = null

    // Call recording (wire 7). remoteTrack is captured on onAddTrack.
    private var remoteTrack: AudioTrack? = null
    private val pcmCb =
        kotlinx.atomicfu.atomic<((ByteArray, Int) -> Unit)?>(null)
    private var recSink: org.webrtc.AudioTrackSink? = null

    // Party-line video (conference).
    private val _video = MutableStateFlow(VideoState())
    override val video: StateFlow<VideoState> = _video
    private var videoSender: RtpSender? = null
    private var capturer: CameraVideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var localVideo: VideoTrack? = null
    private var remoteVideo: VideoTrack? = null

    /** Camera / remote camera track for VideoSurface. Platform members:
     *  a libwebrtc track can't cross into commonMain. */
    val localVideoTrack: VideoTrack? get() = localVideo
    val remoteVideoTrack: VideoTrack? get() = remoteVideo

    private val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: WebRtcIceCandidate?) {
            val c = candidate ?: return
            onCandidate?.invoke(IceCandidate(c.sdp, c.sdpMid, c.sdpMLineIndex))
        }

        override fun onAddTrack(
            receiver: org.webrtc.RtpReceiver?,
            streams: Array<out MediaStream>?,
        ) {
            when (val t = receiver?.track()) {
                is AudioTrack -> {
                    remoteTrack = t
                    if (pcmCb.value != null) attachRec()
                }
                is VideoTrack -> {
                    remoteVideo = t
                    _video.value = _video.value.copy(remoteOn = true)
                }
            }
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
        // Communication mode BEFORE the mic is created: a track captured
        // while AudioManager is still MODE_NORMAL comes up processed for
        // media, not voice, and sounds crunchy until a rejoin. And every
        // link acquires — not only the mic — so the session survives an
        // admin-muted user's up link closing while their down links
        // still play (the refcount is per link on the line, not per
        // mic). CallAudioSession refcounts, so only the first configures
        // and only the last tears down.
        CallAudioSession.acquire(appContext)
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
            // Send-only video transceiver, camera closed. setCameraEnabled
            // swaps a camera track onto the sender with no renegotiation.
            videoSender = runCatching {
                pc.addTransceiver(
                    MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                    RtpTransceiver.RtpTransceiverInit(
                        RtpTransceiver.RtpTransceiverDirection.SEND_ONLY,
                    ),
                ).sender
            }.onFailure {
                Log.w("PartyLine", "no video transceiver; line stays audio-only", it)
            }.getOrNull()
        }
    }

    override suspend fun setCameraEnabled(enabled: Boolean): Boolean {
        val sender = videoSender ?: return false
        if (!enabled) {
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
            Log.w("PartyLine", "could not start the camera", it)
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

    override fun onPcm(sink: ((pcm: ByteArray, sampleRate: Int) -> Unit)?) {
        pcmCb.value = sink
        if (sink == null) detachRec() else attachRec()
    }

    private fun attachRec() {
        detachRec()
        val cb = pcmCb.value ?: return
        // Down links (remote speakers) tap cleanly. Self-mic capture on
        // Android needs JavaAudioDeviceModule.setSamplesReadyCallback
        // wired through WebRtcFactory — a follow-up; until then
        // isCallRecordingSupported stays false on Android so this is
        // staged, not a half-working feature.
        if (sendAudio) return
        val target = remoteTrack ?: return
        val adapter = object : org.webrtc.AudioTrackSink {
            override fun onData(
                audioData: java.nio.ByteBuffer,
                bitsPerSample: Int,
                sampleRate: Int,
                numberOfChannels: Int,
                numberOfFrames: Int,
                absoluteCaptureTimestampMs: Long,
            ) {
                if (bitsPerSample != 16) return
                cb(toMonoLe(audioData, numberOfChannels, numberOfFrames), sampleRate)
            }
        }
        recSink = adapter
        runCatching { target.addSink(adapter) }
            .onFailure { Log.w("PartyLine", "could not tap for recording", it) }
    }

    private fun detachRec() {
        val s = recSink ?: return
        runCatching { remoteTrack?.removeSink(s) }
        recSink = null
    }

    /** WebRTC PCM frame -> 16-bit little-endian mono. Copies (the
     *  buffer may be reused) and downmixes >1 channel. */
    private fun toMonoLe(buf: java.nio.ByteBuffer, channels: Int, frames: Int): ByteArray {
        val dup = buf.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN)
        if (channels <= 1) {
            val out = ByteArray(frames * 2)
            dup.get(out, 0, minOf(out.size, dup.remaining()))
            return out
        }
        val shorts = dup.asShortBuffer()
        val out = ByteArray(frames * 2)
        for (f in 0 until frames) {
            var acc = 0
            for (c in 0 until channels) {
                val i = f * channels + c
                if (i < shorts.limit()) acc += shorts.get(i).toInt()
            }
            val v = acc / channels
            out[f * 2] = (v and 0xFF).toByte()
            out[f * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    // Android's RtpReceiver has no getSynchronizationSources, so the
    // level comes from inbound-rtp stats instead. getStats is async,
    // so a poll refreshes this and audioLevel() reads what it last
    // saw — the caller polls at 250ms anyway.
    @Volatile private var lastLevel: Float? = null
    @Volatile private var statsInFlight = false
    @Volatile private var lastLocalLevel: Float? = null
    @Volatile private var localInFlight = false

    override fun audioLevel(): Float? = level("inbound-rtp")

    /**
     * Our own microphone, from media-source.
     *
     * Not outbound-rtp: that describes what was sent, which is
     * silence-suppressed, so a quiet talker reads as nothing at all.
     * media-source is what the microphone actually heard.
     */
    override fun localAudioLevel(): Float? = level("media-source")

    private fun level(type: String): Float? {
        val local = type == "media-source"
        val busy = if (local) localInFlight else statsInFlight
        if (!busy && !closed.value) {
            if (local) localInFlight = true else statsInFlight = true
            runCatching {
                pc.getStats { report ->
                    var found: Float? = null
                    for (stat in report.statsMap.values) {
                        if (stat.type == type) {
                            (stat.members["audioLevel"] as? Number)?.let {
                                found = it.toFloat()
                            }
                        }
                    }
                    if (local) {
                        lastLocalLevel = found
                        localInFlight = false
                    } else {
                        lastLevel = found
                        statsInFlight = false
                    }
                }
            }.onFailure { if (local) localInFlight = false else statsInFlight = false }
        }
        return if (local) lastLocalLevel else lastLevel
    }

    private val closed = kotlinx.atomicfu.atomic(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { micTrack?.setEnabled(false) }
        runCatching { capturer?.stopCapture() }
        releaseCamera()
        remoteVideo = null
        // The factory is process-wide; see WebRtcFactory.
        runCatching { pc.close() }
        // Every link acquired the session; every link releases it,
        // inside the closed-guard so a double close can't double-
        // decrement. The last one out (up OR down) runs the teardown.
        CallAudioSession.release()
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
