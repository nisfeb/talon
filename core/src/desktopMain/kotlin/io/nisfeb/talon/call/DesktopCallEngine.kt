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
class DesktopCallEngine(configuredIce: List<IceServer> = emptyList()) : CallEngine {

    private val _state = MutableStateFlow(MediaState.Idle)
    override val state: StateFlow<MediaState> = _state

    private val factory = DesktopWebRtcFactory.get()
    private val gathered = CompletableDeferred<Unit>()
    private var micTrack: AudioTrack? = null

    private val rtcConfig = RTCConfiguration().apply {
        for (s in configuredIce) {
            val server = dev.onvoid.webrtc.RTCIceServer()
            server.urls.add(s.url)
            if (s.user.isNotEmpty()) {
                server.username = s.user
                server.password = s.cred
            }
            iceServers.add(server)
        }
    }

    private val pc: RTCPeerConnection = factory.createPeerConnection(
        rtcConfig,
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

    /** A dead STUN/TURN server must degrade, not break: when gathering
     *  stalls (unreachable server keeps ICE in %gathering), proceed
     *  with whatever candidates we have — Tier 0 still works. */
    private suspend fun awaitGathering() {
        runCatching { withTimeout(8_000) { gathered.await() } }
            .onFailure { Log.w("Trunk", "gathering incomplete after 8s — proceeding with partial candidates") }
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

val DesktopCallEngineProvider: CallEngineProvider =
    CallEngineProvider { ice -> DesktopCallEngine(ice) }

/**
 * One [PeerConnectionFactory] for the process.
 *
 * The factory owns libwebrtc's native threads and audio device; it is
 * meant to be shared. One per call — disposed on hang-up — could race
 * the ICE gathering of the call that was ending, which crashed the app
 * seconds after a rejected call. PeerConnections are still closed per
 * call; the factory outlives them.
 */
object DesktopWebRtcFactory {
    private val lock = Any()
    private var factory: PeerConnectionFactory? = null
    private var adm: dev.onvoid.webrtc.media.audio.AudioDeviceModuleBase? = null

    /**
     * What to build the factory's audio device from.
     *
     * The app wants the real one — a sound card, with devices to
     * enumerate and pick between. A headless process wants
     * HeadlessAudioDeviceModule, which has no sound card at all and
     * moves PCM through setAudioSink / setAudioSource instead. Both
     * are AudioDeviceModuleBase, and the factory only ever needs that.
     *
     * Must be set before the first [get] or [audioDeviceModule]: the
     * factory captures the module it was built with, and a later one
     * has no effect on tracks already sourced from the first.
     */
    fun useAudioDeviceModule(make: () -> dev.onvoid.webrtc.media.audio.AudioDeviceModuleBase) {
        synchronized(lock) {
            check(factory == null) { "the WebRTC factory is already built" }
            makeAdm = make
        }
    }

    private var makeAdm: () -> dev.onvoid.webrtc.media.audio.AudioDeviceModuleBase =
        { dev.onvoid.webrtc.media.audio.AudioDeviceModule() }

    /**
     * The module the factory captures and plays through.
     *
     * Held here because device selection has to go through the *same*
     * instance the factory was built with — an ADM created later has no
     * effect on tracks already sourced from another one. Created eagerly
     * with the factory so the first call and a device change made before
     * any call both land on one object.
     */
    fun audioDeviceModule(): dev.onvoid.webrtc.media.audio.AudioDeviceModuleBase =
        synchronized(lock) { ensure().second }

    fun get(): PeerConnectionFactory = synchronized(lock) { ensure().first }

    private fun ensure(): Pair<PeerConnectionFactory, dev.onvoid.webrtc.media.audio.AudioDeviceModuleBase> {
        val f = factory
        val a = adm
        if (f != null && a != null) return f to a
        val module = makeAdm()
        val made = PeerConnectionFactory(module)
        factory = made
        adm = module
        return made to module
    }
}
