package io.nisfeb.talon.call

import io.nisfeb.talon.urbit.UrbitChannel
import io.nisfeb.talon.urbit.UrbitSession
import io.nisfeb.talon.util.Log
import io.nisfeb.talon.util.backgroundExceptionHandler
import io.nisfeb.talon.util.ioDispatcher
import io.nisfeb.talon.util.nowMs
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** What the call UI renders. One active call at a time (v0). */
sealed interface CallUiState {
    data object None : CallUiState
    data class Outgoing(val peer: String) : CallUiState
    data class Incoming(val peer: String) : CallUiState
    data class Active(val peer: String, val media: MediaState, val muted: Boolean) : CallUiState
    data class Ended(val peer: String, val reason: String) : CallUiState
}

/**
 * The signaling half of a 1:1 call — design §04's state machine.
 * Owns its own eyre channel (subscription to %trunk /calls) and one
 * [CallEngine] per call. All WebRTC knowledge stays in the engine;
 * all %trunk knowledge stays here.
 *
 * v0 spike: instruments the two numbers the design says to measure
 * first — signaling RTT (offer→answer) and time-to-live-media. Grep
 * logs for "Trunk metric".
 */
@OptIn(ExperimentalUuidApi::class)
class CallController(
    private val session: UrbitSession,
    private val engineProvider: CallEngineProvider,
    /** How long a phone rings before giving up. Without this a caller
     *  that vanishes mid-ring (app killed, crash, network drop) leaves
     *  the callee ringing internally forever — and a device that thinks
     *  it is ringing answers "busy" to every call after it. */
    private val ringTimeoutMs: Long = 45_000L,
    /** How long a call may sit "connecting" before we give up on it. */
    private val connectTimeoutMs: Long = 60_000L,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher + backgroundExceptionHandler)
    private val _state = MutableStateFlow<CallUiState>(CallUiState.None)
    val state: StateFlow<CallUiState> = _state.asStateFlow()

    private var channel: UrbitChannel? = null
    private var loop: Job? = null

    // Per-call context. Guarded by single-threaded discipline: every
    // mutation happens inside `scope` (a single logical actor for v0).
    @Volatile private var iceServers: List<IceServer> = emptyList()
    private var callId: String? = null
    private var peer: String? = null
    private var engine: CallEngine? = null
    // Completed when the peer's offer lands. A user can answer while
    // the ring is still going and the offer still in flight (slow
    // gather on the caller's side) — accept() awaits this instead of
    // racing it.
    private var pendingOffer = CompletableDeferred<SessionDesc>()
    private var mediaWatch: Job? = null
    // Ended is a notice, not a call: it must never make us look busy.
    // It used to, and because it was only cleared by the overlay's
    // timer, a device whose UI wasn't composed (backgrounded phone,
    // failed first attempt) answered "busy" to every ring afterwards.
    private val isFree: Boolean
        get() = _state.value.let { it is CallUiState.None || it is CallUiState.Ended }

    // Bumped per call so a stale auto-clear can't wipe a newer one.
    private var endedToken = 0
    // Same idea for the ring watchdog: answering, ending, or starting
    // another call must disarm the one already in flight.
    private var ringToken = 0
    private var connectToken = 0
    private var tPlaced = 0L
    private var tOfferSent = 0L

    fun start() {
        if (loop != null) return
        loop = scope.launch { runLoop() }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        endLocal("stopped")
    }

    private suspend fun runLoop() {
        var backoff = 2_000L
        while (scope.isActive) {
            runCatching {
                val ch = session.openChannel()
                channel = ch
                // The ship's advertised ICE servers (its sidecar / its
                // sponsor's). Best-effort: no config means Tier 0 only.
                runCatching { iceServers = TrunkWire.parseIce(ch.scry(TrunkWire.AGENT, "/ice")) }
                    .onSuccess { Log.i(TAG, "ice config: ${iceServers.size} servers") }
                    .onFailure { Log.w(TAG, "ice scry failed (Tier 0 only)", it) }
                ch.events().let { events ->
                    ch.subscribe(TrunkWire.AGENT, TrunkWire.CALLS_PATH)
                    backoff = 2_000L
                    events.collect { ev ->
                        ev.id?.let { runCatching { ch.ack(it) } }
                        val body = ev.body as? JsonObject ?: return@collect
                        // Surface poke nacks — a silently-refused poke cost
                        // us a day of "the accept never arrives" debugging.
                        body["err"]?.let { Log.e(TAG, "channel error: $it") }
                        val fact = body["json"] ?: return@collect
                        when (val up = TrunkWire.parseUpdate(fact)) {
                            is TrunkUpdate.Recv -> onSignal(up)
                            is TrunkUpdate.Ticket -> onTicket?.invoke(up.ticket)
                            is TrunkUpdate.Denied -> {
                                Log.w(TAG, "room " + up.name + " denied: " + up.why)
                                onDenied?.invoke(up.name, up.why)
                            }
                            null -> {}
                        }
                    }
                }
            }.onFailure { Log.w(TAG, "signal loop ended", it) }
            if (!scope.isActive) break
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
    }

    // ── outbound actions ──────────────────────────────────────────

    fun placeCall(target: String) {
        scope.launch {
            if (!isFree) return@launch
            val id = Uuid.random().toString()
            callId = id
            peer = target
            tPlaced = nowMs()
            _state.value = CallUiState.Outgoing(target)
            armRingWatchdog()
            poke(target, TrunkSig.Ring(id))
            // Gather while the far end rings (design D3: the slow parts overlap).
            val eng = engineProvider.create(iceServers)
            engine = eng
            watchMedia(eng, target)
            runCatching { eng.createOffer() }
                .onSuccess { offer ->
                    tOfferSent = nowMs()
                    Log.i(TAG, "Trunk metric: gather took ${tOfferSent - tPlaced}ms")
                    poke(target, TrunkSig.Offer(id, offer.sdp, offer.fingerprint))
                }
                .onFailure {
                    Log.e(TAG, "offer failed", it)
                    poke(target, TrunkSig.Hangup(id))
                    endLocal(it.message ?: "media error")
                }
        }
    }

    fun accept() {
        scope.launch {
            ringToken++ // answered: stop the give-up timer
            val id = callId ?: return@launch
            val from = peer ?: return@launch
            _state.value = CallUiState.Active(from, MediaState.Connecting, muted = false)
            armConnectWatchdog()
            val offer = runCatching {
                kotlinx.coroutines.withTimeout(20_000) { pendingOffer.await() }
            }.getOrElse {
                Log.e(TAG, "no offer within 20s of answering")
                poke(from, TrunkSig.Hangup(id))
                endLocal("no offer arrived")
                return@launch
            }
            val eng = engineProvider.create(iceServers)
            engine = eng
            watchMedia(eng, from)
            runCatching { eng.acceptOffer(offer) }
                .onSuccess { answer ->
                    poke(from, TrunkSig.Accept(id, answer.sdp, answer.fingerprint))
                }
                .onFailure {
                    Log.e(TAG, "answer failed", it)
                    poke(from, TrunkSig.Hangup(id))
                    endLocal(it.message ?: "media error")
                }
        }
    }

    fun reject() {
        scope.launch {
            val id = callId ?: return@launch
            peer?.let { poke(it, TrunkSig.Reject(id, "declined")) }
            endLocal("declined")
        }
    }

    fun hangup() {
        scope.launch {
            val id = callId ?: return@launch
            peer?.let { poke(it, TrunkSig.Hangup(id)) }
            endLocal("hung up")
        }
    }

    fun setMuted(muted: Boolean) {
        engine?.setMuted(muted)
        val cur = _state.value
        if (cur is CallUiState.Active) _state.value = cur.copy(muted = muted)
    }

    /** Clear a transient Ended banner. */
    fun dismissEnded() {
        if (_state.value is CallUiState.Ended) _state.value = CallUiState.None
    }

    // ── inbound signals ───────────────────────────────────────────

    /** Set by the party-line surface: a host granted us a room ticket. */
    var onTicket: ((TrunkTicket) -> Unit)? = null

    /** A host refused us a line. Surfaced so tapping the button always
     *  says something — silence reads as a broken button. */
    var onDenied: ((name: String, why: String) -> Unit)? = null

    /** Host a party line: [members] are the ships allowed to join.
     *  The agent announces it to each of them. */
    suspend fun openRoom(name: String, title: String, members: List<String>) {
        val ch = channel ?: return
        runCatching {
            ch.poke(
                TrunkWire.AGENT, TrunkWire.ACTION_MARK,
                TrunkWire.openRoomAction(name, title, members),
            )
        }.onFailure { Log.e(TAG, "open-room poke failed", it) }
    }

    /** Ask [host] to let us onto its party line. */
    suspend fun joinRoom(host: String, name: String) {
        val ch = channel ?: return
        runCatching {
            ch.poke(
                TrunkWire.AGENT, TrunkWire.ACTION_MARK,
                TrunkWire.joinRoomAction(host, name),
            )
        }.onFailure { Log.e(TAG, "join-room poke failed", it) }
    }

    private suspend fun onSignal(recv: TrunkUpdate.Recv) {
        val sig = recv.sig
        when (sig) {
            is TrunkSig.Ring -> {
                if (!isFree) {
                    // Stay silent rather than replying busy.
                    //
                    // A ship is one identity across many devices, and
                    // every one of them gets this ring — that is the
                    // point. Rejecting would speak for all of them: a
                    // phone mid-call would cancel a ring the desktop
                    // was about to answer, which is how "it just says
                    // busy" happened even when a device was visibly
                    // ringing. Whoever is free answers; if nobody does,
                    // the caller's own ring watchdog ends it.
                    Log.i(TAG, "ignoring ring from " + recv.from + "; this device is " + _state.value)
                    return
                }
                callId = sig.id
                peer = recv.from
                pendingOffer = CompletableDeferred()
                _state.value = CallUiState.Incoming(recv.from)
                armRingWatchdog()
            }
            is TrunkSig.Offer -> {
                if (sig.id != callId) return
                // Cross-check the signaled fingerprint against the SDP's own
                // a=fingerprint line — catches a tampered relay.
                // ponytail: v0 verifies signaling consistency; pinning the
                // live DTLS handshake to fpr lands with the engine work.
                if (sdpFingerprint(sig.sdp) != sig.fpr) {
                    Log.e(TAG, "fingerprint mismatch from ${recv.from} — dropping call")
                    poke(recv.from, TrunkSig.Reject(sig.id, "fingerprint mismatch"))
                    endLocal("security error")
                    return
                }
                pendingOffer.complete(SessionDesc(sig.sdp, sig.fpr))
            }
            is TrunkSig.Accept -> {
                if (sig.id != callId) return
                if (sdpFingerprint(sig.sdp) != sig.fpr) {
                    Log.e(TAG, "fingerprint mismatch from ${recv.from} — dropping call")
                    endCall("security error")
                    return
                }
                Log.i(TAG, "Trunk metric: offer→answer RTT ${nowMs() - tOfferSent}ms")
                val eng = engine ?: return
                _state.value = CallUiState.Active(recv.from, MediaState.Connecting, muted = false)
                armConnectWatchdog()
                runCatching { eng.setAnswer(SessionDesc(sig.sdp, sig.fpr)) }
                    .onFailure {
                        Log.e(TAG, "setAnswer failed", it)
                        endCall("media error")
                    }
            }
            is TrunkSig.Reject -> {
                if (sig.id != callId) return
                endLocal(if (sig.reason == "busy") "busy" else "declined")
            }
            is TrunkSig.Hangup -> {
                if (sig.id != callId) return
                endLocal("ended")
            }
        }
    }

    /** Give up on a call that never finishes connecting. Only a call
     *  with live media is allowed to hold the device indefinitely —
     *  anything else eventually frees it, so no sequence of failures
     *  can leave a device permanently "busy". */
    private fun armConnectWatchdog() {
        val token = ++connectToken
        scope.launch {
            delay(connectTimeoutMs)
            if (connectToken != token) return@launch
            val cur = _state.value
            if (cur is CallUiState.Active && cur.media != MediaState.Live) {
                Log.w(TAG, "media never connected after ${'$'}{connectTimeoutMs}ms")
                endCall("couldn't connect")
            }
        }
    }

    private fun watchMedia(eng: CallEngine, withPeer: String) {
        mediaWatch?.cancel()
        mediaWatch = scope.launch {
            eng.state.collect { media ->
                when (media) {
                    MediaState.Live -> {
                        connectToken++ // connected: stop the give-up timer
                        if (tPlaced != 0L) {
                            Log.i(TAG, "Trunk metric: place→live ${nowMs() - tPlaced}ms")
                        }
                        val cur = _state.value
                        if (cur is CallUiState.Active) _state.value = cur.copy(media = media)
                    }
                    MediaState.Failed -> endCall("connection failed")
                    else -> {
                        val cur = _state.value
                        if (cur is CallUiState.Active) _state.value = cur.copy(media = media)
                    }
                }
            }
        }
    }

    private fun endCall(reason: String) {
        val id = callId
        val p = peer
        if (id != null && p != null) scope.launch { poke(p, TrunkSig.Hangup(id)) }
        endLocal(reason)
    }

    private fun endLocal(reason: String) {
        ringToken++
        connectToken++
        mediaWatch?.cancel()
        mediaWatch = null
        engine?.close()
        engine = null
        pendingOffer = CompletableDeferred()
        val p = peer
        callId = null
        peer = null
        tPlaced = 0L
        if (p == null) {
            _state.value = CallUiState.None
            return
        }
        _state.value = CallUiState.Ended(p, reason)
        val token = ++endedToken
        scope.launch {
            delay(ENDED_NOTICE_MS)
            if (endedToken == token && _state.value is CallUiState.Ended) {
                _state.value = CallUiState.None
            }
        }
    }

    /** Give up on a ring nobody answered, on either side. */
    private fun armRingWatchdog() {
        val token = ++ringToken
        val id = callId
        val target = peer
        scope.launch {
            delay(ringTimeoutMs)
            if (ringToken != token) return@launch
            when (_state.value) {
                is CallUiState.Outgoing -> {
                    // Tell the far end to stop ringing before we forget
                    // the call id.
                    if (id != null && target != null) poke(target, TrunkSig.Hangup(id))
                    Log.i(TAG, "no answer after ${ringTimeoutMs}ms")
                    endLocal("no answer")
                }
                is CallUiState.Incoming -> {
                    Log.i(TAG, "missed call from ${'$'}target")
                    endLocal("missed")
                }
                else -> {}
            }
        }
    }

    private suspend fun poke(target: String, sig: TrunkSig) {
        val ch = channel ?: return
        runCatching {
            ch.poke(TrunkWire.AGENT, TrunkWire.ACTION_MARK, TrunkWire.sendAction(target, sig))
        }.onFailure { Log.e(TAG, "poke ${sig::class.simpleName} failed", it) }
    }

    companion object {
        private const val TAG = "Trunk"

        /** How long the "call ended" notice lingers before the surface
         *  goes quiet. Cleared here, not in the UI, so a backgrounded
         *  app still frees itself to take the next call. */
        private const val ENDED_NOTICE_MS = 5_000L
    }
}
