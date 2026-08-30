package io.nisfeb.talon.call

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * iOS [CallEngine]: the 1:1 media half, backed by the same Swift
 * WebRTC implementation the party lines use.
 *
 * Non-trickle, unlike [IosPeerLink]: a 1:1 call exchanges one complete
 * SDP in each direction over ames, so the Swift peer must finish ICE
 * gathering before it hands the SDP back (trunkline design D3). The
 * fingerprint is read out of the SDP the same way every other platform
 * does it, so the pinning check in CallController stays identical.
 */
class IosCallEngine(private val native: NativeRtcPeer) : CallEngine {

    private val _state = MutableStateFlow(MediaState.Idle)
    override val state: StateFlow<MediaState> = _state.asStateFlow()

    private val _video = MutableStateFlow(VideoState())
    override val video: StateFlow<VideoState> = _video.asStateFlow()

    init {
        native.onStateChange { _state.value = it }
        native.onVideoChange { _video.value = it }
    }

    /** The views [VideoSurface] renders. iOS-only members. */
    fun localView(): Any? = native.localVideoView()
    fun remoteView(): Any? = native.remoteVideoView()

    override suspend fun setCameraEnabled(enabled: Boolean): Boolean =
        suspendCancellableCoroutine { cont ->
            native.setCameraEnabled(enabled) { err ->
                if (err != null) io.nisfeb.talon.util.Log.w(TAG, "camera: $err")
                cont.resume(err == null)
            }
        }

    override suspend fun createOffer(): SessionDesc {
        _state.value = MediaState.Gathering
        val sdp = await { done -> native.createOffer(done) }
        _state.value = MediaState.Connecting
        return SessionDesc(sdp, sdpFingerprint(sdp))
    }

    override suspend fun acceptOffer(remote: SessionDesc): SessionDesc {
        _state.value = MediaState.Gathering
        val sdp = await { done -> native.createAnswer(remote.sdp, done) }
        _state.value = MediaState.Connecting
        return SessionDesc(sdp, sdpFingerprint(sdp))
    }

    override suspend fun setAnswer(remote: SessionDesc) {
        suspendCancellableCoroutine { cont ->
            native.applyAnswer(remote.sdp) { err ->
                if (err == null) cont.resume(Unit)
                else cont.resumeWithException(IllegalStateException(err))
            }
        }
    }

    override fun setMuted(muted: Boolean) {
        native.setMuted(muted)
    }

    private companion object { private const val TAG = "IosCallEngine" }

    private val closed = atomic(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        native.close()
        _state.value = MediaState.Closed
    }
}

/** 1:1 engines: non-trickle, always capturing the mic. */
class IosCallEngineProvider(private val rtc: NativeRtcFactory) : CallEngineProvider {
    override fun create(iceServers: List<IceServer>): CallEngine =
        IosCallEngine(rtc.create(iceServers, sendAudio = true, trickle = false))
}
