package io.nisfeb.talon.call

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * iOS [PeerLink]: one party-line stream, trickling ICE, backed by the
 * Swift WebRTC implementation behind [NativeRtcPeer].
 *
 * All this adds is the shape conversion — callbacks to suspend, a
 * listener to a StateFlow. The media itself is Swift's.
 */
class IosPeerLink(private val native: NativeRtcPeer) : PeerLink {

    private val _state = MutableStateFlow(MediaState.Idle)
    override val state: StateFlow<MediaState> = _state.asStateFlow()

    init {
        native.onStateChange { _state.value = it }
    }

    override fun onLocalCandidate(callback: (IceCandidate) -> Unit) {
        native.onIceCandidate(callback)
    }

    override suspend fun offer(): String {
        val sdp = await { done -> native.createOffer(done) }
        _state.value = MediaState.Connecting
        return sdp
    }

    override suspend fun answerTo(remoteSdp: String): String {
        val sdp = await { done -> native.createAnswer(remoteSdp, done) }
        _state.value = MediaState.Connecting
        return sdp
    }

    override suspend fun applyAnswer(remoteSdp: String) {
        suspendCancellableCoroutine { cont ->
            native.applyAnswer(remoteSdp) { err ->
                if (err == null) cont.resume(Unit)
                else cont.resumeWithException(IllegalStateException(err))
            }
        }
    }

    override fun addRemoteCandidate(candidate: IceCandidate) {
        native.addIceCandidate(candidate)
    }

    override fun setMuted(muted: Boolean) {
        native.setMuted(muted)
    }

    private val closed = atomic(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        native.close()
        _state.value = MediaState.Closed
    }
}

/**
 * Bridge a `(sdp, error) -> Unit` callback into a suspend call.
 * Swift hands back exactly one of the two; a call that somehow
 * produces neither is treated as a failure rather than hanging the
 * coroutine forever.
 */
internal suspend fun await(call: ((String?, String?) -> Unit) -> Unit): String =
    suspendCancellableCoroutine { cont ->
        call { value, err ->
            when {
                value != null -> cont.resume(value)
                else -> cont.resumeWithException(
                    IllegalStateException(err ?: "webrtc call returned nothing"),
                )
            }
        }
    }

/** Party-line links: trickling, mic only on the up link. */
class IosPeerLinkFactory(private val rtc: NativeRtcFactory) : PeerLinkFactory {
    override fun create(iceServers: List<IceServer>, sendAudio: Boolean): PeerLink =
        IosPeerLink(rtc.create(iceServers, sendAudio, trickle = true))
}
