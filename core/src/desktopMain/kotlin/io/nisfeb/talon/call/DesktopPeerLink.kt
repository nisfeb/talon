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
import dev.onvoid.webrtc.media.MediaDevices
import dev.onvoid.webrtc.media.MediaStreamTrack
import dev.onvoid.webrtc.media.audio.AudioTrack
import dev.onvoid.webrtc.media.video.VideoCaptureCapability
import dev.onvoid.webrtc.media.video.VideoDeviceSource
import dev.onvoid.webrtc.media.video.VideoTrack
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

    // Call recording (wire 7). remoteTrack is captured on onTrack so a
    // down link can be tapped; the callback + adapter sink are set by
    // onPcm. atomic because onTrack fires on a native thread.
    private var remoteTrack: AudioTrack? = null
    private val pcmCb =
        kotlinx.atomicfu.atomic<((ByteArray, Int) -> Unit)?>(null)
    private var recSink: dev.onvoid.webrtc.media.audio.AudioTrackSink? = null
    private var micRecorder: dev.onvoid.webrtc.media.audio.AudioRecorder? = null
    private val recFrames = kotlinx.atomicfu.atomic(0)

    // Party-line video (conference). The up link (sendAudio) carries a
    // pre-negotiated send video transceiver whose camera track is
    // swapped in by setCameraEnabled; a down link receives a remote
    // camera track via onTrack. VideoState drives the tile renderer.
    private val _video = MutableStateFlow(VideoState())
    override val video: StateFlow<VideoState> = _video
    private var cameraSource: VideoDeviceSource? = null
    // Read from the UI thread via the getters below; written from the
    // native onTrack thread (remote) and the camera coroutine (local).
    @kotlin.concurrent.Volatile
    private var localVideo: VideoTrack? = null
    @kotlin.concurrent.Volatile
    private var remoteVideo: VideoTrack? = null

    /** The camera / remote camera track for [io.nisfeb.talon.ui.VideoSurface]
     *  to render. Platform members: a libwebrtc track can't cross into
     *  commonMain. */
    val localVideoTrack: VideoTrack? get() = localVideo
    val remoteVideoTrack: VideoTrack? get() = remoteVideo

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
                when (val track = transceiver?.receiver?.track) {
                    is AudioTrack -> {
                        remoteTrack = track
                        remotePcm?.let { s ->
                            runCatching { track.addSink(s) }
                                .onFailure { Log.w("PartyLine", "could not tap the remote track", it) }
                        }
                        // Recording started before this speaker's track arrived.
                        if (pcmCb.value != null) attachRec()
                    }
                    is VideoTrack -> {
                        remoteVideo = track
                        _video.value = _video.value.copy(remoteOn = true)
                    }
                    else -> {}
                }
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
            val source = micPcm ?: factory.createAudioSource(micAudioOptions())
            val track = factory.createAudioTrack("talon-mic", source)
            // Galène requires every stream to be one-directional: the
            // offerer is always the sender.
            pc.addTransceiver(track, dev.onvoid.webrtc.RTCRtpTransceiverInit().apply {
                direction = RTCRtpTransceiverDirection.SEND_ONLY
            })
            micTrack = track
            // A send-only video transceiver on the up stream, camera
            // closed. setCameraEnabled swaps the camera track onto it
            // later with no renegotiation; the SFU forwards it to any
            // subscriber that requested video. An audio-only room just
            // never requests it.
            runCatching {
                val camSource = VideoDeviceSource()
                val camTrack = factory.createVideoTrack("talon-cam", camSource)
                camTrack.isEnabled = false
                pc.addTransceiver(camTrack, dev.onvoid.webrtc.RTCRtpTransceiverInit().apply {
                    direction = RTCRtpTransceiverDirection.SEND_ONLY
                })
                cameraSource = camSource
                localVideo = camTrack
            }.onFailure { Log.w("PartyLine", "no video transceiver; line stays audio-only", it) }
        }
    }

    override suspend fun setCameraEnabled(enabled: Boolean): Boolean {
        val source = cameraSource ?: return false
        val track = localVideo ?: return false
        if (!enabled) {
            runCatching { track.isEnabled = false }
            runCatching { source.stop() }
            _video.value = _video.value.copy(localOn = false)
            return true
        }
        // Already on (e.g. a double-tap): don't re-open the source.
        if (localVideo?.isEnabled == true) return true
        return runCatching {
            val device = MediaDevices.getVideoCaptureDevices().firstOrNull()
                ?: error("no camera on this machine")
            source.setVideoCaptureDevice(device)
            source.setVideoCaptureCapability(VideoCaptureCapability(640, 480, 30))
            source.start()
            track.isEnabled = true
            _video.value = _video.value.copy(localOn = true)
            true
        }.getOrElse {
            Log.w("PartyLine", "could not start the camera", it)
            runCatching { source.stop() }
            false
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
        // Guarded: close() disposes the track, and a mute tap racing a
        // republish/hang-up would call into freed native memory.
        if (closed.value) return
        runCatching { micTrack?.isEnabled = !muted }
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

    override fun onPcm(sink: ((pcm: ByteArray, sampleRate: Int) -> Unit)?) {
        pcmCb.value = sink
        if (sink == null) detachRec() else attachRec()
    }

    private fun attachRec() {
        detachRec()
        val cb = pcmCb.value ?: return
        recFrames.value = 0
        if (sendAudio) {
            // Self-mic: a LOCAL track's addSink never fires in webrtc-java
            // (a solo recording captured only silence), so tap the mic
            // with a standalone AudioRecorder. It's separate from the
            // peer connection, so it cannot affect the outgoing call.
            val device = runCatching {
                DesktopWebRtcFactory.audioDeviceModule().recordingDevices.firstOrNull()
            }.getOrNull() ?: runCatching {
                dev.onvoid.webrtc.media.audio.AudioDeviceModule().recordingDevices.firstOrNull()
            }.getOrNull()
            if (device == null) {
                Log.w("PartyLine", "record tap: no recording device; mic not captured")
                return
            }
            runCatching {
                val rec = dev.onvoid.webrtc.media.audio.AudioRecorder()
                rec.setAudioDevice(device)
                rec.setAudioSink(object : dev.onvoid.webrtc.media.audio.AudioSink {
                    // Arg order verified against webrtc-java 0.14.0:
                    // (data, nSamples, bytesPerSample, channels, sampleRate,
                    //  captureDelayMs, clockDrift).
                    override fun onRecordedData(
                        data: ByteArray,
                        nSamples: Int,
                        bytesPerSample: Int,
                        channels: Int,
                        sampleRate: Int,
                        captureDelayMs: Int,
                        clockDrift: Int,
                    ) {
                        if (bytesPerSample != 2) return
                        val ch = if (channels in 1..2) channels else 1
                        val frames = data.size / (ch * 2)
                        if (recFrames.getAndIncrement() == 0) {
                            Log.i("PartyLine", "record tap: mic first frame rate=$sampleRate ch=$ch bytes=${data.size}")
                        }
                        cb(toMonoLe(data, ch, frames), sampleRate)
                    }
                })
                rec.start()
                micRecorder = rec
                Log.i("PartyLine", "record tap: mic AudioRecorder started")
            }.onFailure { Log.w("PartyLine", "record tap: mic recorder failed", it) }
            return
        }
        // Down link: the remote speaker's decoded track sink DOES fire.
        val target = remoteTrack ?: run {
            Log.w("PartyLine", "record tap: no remote track yet")
            return
        }
        val adapter = object : dev.onvoid.webrtc.media.audio.AudioTrackSink {
            override fun onData(
                audioData: ByteArray,
                bitsPerSample: Int,
                sampleRate: Int,
                numberOfChannels: Int,
                numberOfFrames: Int,
            ) {
                if (bitsPerSample != 16) return
                if (recFrames.getAndIncrement() == 0) {
                    Log.i("PartyLine", "record tap: remote first frame rate=$sampleRate ch=$numberOfChannels")
                }
                cb(toMonoLe(audioData, numberOfChannels, numberOfFrames), sampleRate)
            }
        }
        recSink = adapter
        runCatching { target.addSink(adapter) }
            .onFailure { Log.w("PartyLine", "could not tap remote for recording", it) }
    }

    private fun detachRec() {
        micRecorder?.let {
            runCatching { it.stop() }
            Log.i("PartyLine", "record tap: mic recorder stopped after ${recFrames.value} frames")
        }
        micRecorder = null
        val s = recSink ?: return
        runCatching { remoteTrack?.removeSink(s) }
        Log.i("PartyLine", "record tap: remote detached after ${recFrames.value} frames")
        recSink = null
    }

    /** WebRTC PCM frame -> 16-bit little-endian mono bytes. Copies,
     *  because webrtc may reuse the delivered array across callbacks.
     *  Downmixes >1 channel by averaging. */
    private fun toMonoLe(
        pcm: ByteArray,
        channels: Int,
        frames: Int,
    ): ByteArray {
        if (channels <= 1) return pcm.copyOf()
        val out = ByteArray(frames * 2)
        for (f in 0 until frames) {
            var acc = 0
            for (c in 0 until channels) {
                val b = (f * channels + c) * 2
                if (b + 1 < pcm.size) {
                    val lo = pcm[b].toInt() and 0xFF
                    val hi = pcm[b + 1].toInt()
                    acc += (hi shl 8) or lo
                }
            }
            val v = acc / channels
            out[f * 2] = (v and 0xFF).toByte()
            out[f * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

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
        detachRec()
        runCatching { localVideo?.isEnabled = false }
        runCatching { cameraSource?.stop() }
        localVideo = null
        remoteVideo = null
        runCatching { micTrack?.isEnabled = false }
        runCatching { pc.close() }
        // Ours to free even when the source is a caller-owned
        // CustomAudioSource — and up links republish on flaky
        // networks, so this leaked per republish, not per call. The
        // factory-made AudioTrackSource can't be freed: webrtc-java
        // 0.14.0 exposes no dispose on it.
        runCatching { micTrack?.dispose() }
        micTrack = null
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
