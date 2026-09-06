package io.nisfeb.talon.call

import io.nisfeb.talon.urbit.UrbitChannel
import io.nisfeb.talon.urbit.PokeNacked
import io.nisfeb.talon.urbit.UrbitSession
import io.nisfeb.talon.util.Log
import io.nisfeb.talon.util.backgroundExceptionHandler
import io.nisfeb.talon.util.ioDispatcher
import io.nisfeb.talon.util.nowMs
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Whether to offer installing %trunk, and how that is going.
 *
 * Calls need the desk on both ships and it isn't part of %base, so a
 * user who has never installed it would otherwise tap the call button
 * and get nothing at all.
 */
sealed interface TrunkInstall {
    data object Hidden : TrunkInstall
    data object Offered : TrunkInstall

    /**
     * The desk is installed but speaks an older wire than this client.
     * The remedy is the same poke as installing — kiln fetches the
     * publisher's current version — so it shares this flow.
     */
    data class Outdated(val shipWire: Int, val ourWire: Int) : TrunkInstall
    data object Installing : TrunkInstall
    data class Failed(val why: String) : TrunkInstall
}

/** An anonymous listen link, and when it stops working. */
data class ListenLink(val room: String, val url: String, val expiresSecs: Long)

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
    private val ringTimeoutMs: Long = DEFAULT_RING_TIMEOUT_MS,
    /** How long a call may sit "connecting" before we give up on it. */
    private val connectTimeoutMs: Long = 60_000L,
    /** Call tones. Noop where a platform has no playback path, so
     *  nothing here has to check. */
    private val sounds: CallSoundPlayer = CallSoundPlayer.Noop,
    /** How long a party-line ask may sit unanswered before the pending
     *  indicator gives up on the host. A knob for tests. */
    private val joinAskTimeoutMs: Long = JOIN_ASK_TIMEOUT_MS,
    /** Fallback ICE and sidecar this build ships, adopted by a ship
     *  that has none. [CallDefaults.None] adopts nothing. */
    private val defaults: CallDefaults = CallDefaults.None,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher + backgroundExceptionHandler)
    private val _state = MutableStateFlow<CallUiState>(CallUiState.None)

    /**
     * Who may ring this ship, or null if we don't know yet.
     *
     * Null is load-bearing: %trunk is a desk the user installs by
     * hand, so a ship without it answers the scry with an error. That
     * has to stay distinguishable from "installed, and set to open" —
     * otherwise the settings screen shows an editor whose every poke
     * is nacked, which reads as a broken toggle rather than a missing
     * desk. The agent enforces the policy; this is only a read model.
     */
    private val _policy = MutableStateFlow<CallPolicy?>(null)
    val state: StateFlow<CallUiState> = _state.asStateFlow()

    init {
        // Tones follow the call state rather than being sprinkled
        // through placeCall / accept / reject / hangup / the watchdogs.
        // Every one of those already moves the state, and half of them
        // are reached from more than one path.
        scope.launch {
            _state.collect { st ->
                when (st) {
                    is CallUiState.Outgoing ->
                        sounds.loop(CallSounds.ringback(), RINGBACK_GAP_MS)
                    is CallUiState.Incoming ->
                        // The only tone that honours the silent
                        // switch: it is the one the user silenced.
                        sounds.loop(
                            CallSounds.incoming(), INCOMING_GAP_MS, ToneStream.Ringer,
                        )
                    else -> sounds.stopLoop()
                }
            }
        }
    }
    val policy: StateFlow<CallPolicy?> = _policy.asStateFlow()

    /**
     * The most recently minted anonymous listen link, or null. Held
     * rather than fired-and-forgotten so the UI can show it until the
     * user has actually copied it somewhere.
     */
    /**
     * Every party line this ship can see: the ones it hosts, and the
     * ones it has been invited onto, keyed "~host/name".
     *
     * This is what decides whether a group shows a call button at all
     * — no line, no button — and what the admin switches read their
     * current state from.
     */
    private val _rooms = MutableStateFlow<Map<String, PartyRoom>>(emptyMap())
    val rooms: StateFlow<Map<String, PartyRoom>> = _rooms.asStateFlow()

    private val _invites = MutableStateFlow<Map<String, PartyInvite>>(emptyMap())
    val invites: StateFlow<Map<String, PartyInvite>> = _invites.asStateFlow()

    /** The line for [host]/[name], from whichever side we know it. */
    fun lineFor(host: String, name: String): PartyRoom? {
        val key = "$host/$name"
        _rooms.value[key]?.let { return it }
        return _invites.value[key]?.let {
            PartyRoom(it.name, it.title, it.listen, it.sfuBase, it.sfuBase.isNotEmpty())
        }
    }

    /**
     * The sidecar this ship uses when a room doesn't name its own.
     * Surfaced so the admin screen can show which server a group is
     * actually on, rather than "the host's" with no way to see it.
     */
    private val _shipSfuBase = MutableStateFlow("")
    val shipSfuBase: StateFlow<String> = _shipSfuBase.asStateFlow()

    /** "host/room" this device asked to join, or null. Exposed so the
     *  bar can show "asking the host…" the moment the button is
     *  tapped — the grant rides ames and a sleeping host answers in
     *  seconds-to-never, and silence reads as a dead button. */
    private val _pendingJoin = MutableStateFlow<String?>(null)
    val pendingJoin: StateFlow<String?> = _pendingJoin.asStateFlow()

    /** Peeks in flight, by "host/name". A peek is answered
     *  asynchronously — an %open fact on success, a %denied fact
     *  otherwise — so the poke's own ack says nothing. This is how
     *  those answers find their way back to [peekFailed].
     *  A StateFlow, not a plain set: peekRoom mutates from the UI
     *  dispatcher while the signal loop mutates from ioDispatcher,
     *  and a torn LinkedHashSet iteration inside events.collect
     *  would tear down the whole SSE channel. */
    private val pendingPeeks = MutableStateFlow<Set<String>>(emptySet())

    /** The wire the ship speaks, once known. */
    private val _wire = MutableStateFlow(0)
    val wire: StateFlow<Int> = _wire.asStateFlow()

    private val _listenLink = MutableStateFlow<ListenLink?>(null)
    val listenLink: StateFlow<ListenLink?> = _listenLink.asStateFlow()

    fun clearListenLink() { _listenLink.value = null }

    private val _install = MutableStateFlow<TrunkInstall>(TrunkInstall.Hidden)
    val install: StateFlow<TrunkInstall> = _install.asStateFlow()

    /** No %trunk (or one too old to answer /x/policy) — see [_policy]. */
    private val trunkMissing: Boolean get() = _policy.value == null

    private var channel: UrbitChannel? = null
    private var loop: Job? = null

    // Per-call context. Guarded by single-threaded discipline: every
    // mutation happens inside `scope` (a single logical actor for v0).
    private val _ice = MutableStateFlow<List<IceServer>>(emptyList())

    /**
     * The STUN/TURN servers in play, as this ship advertises them.
     *
     * Exposed so the settings editor can show what a call will
     * actually use — an empty list means every call off the local
     * network fails, and that is worth being able to see.
     */
    val ice: StateFlow<List<IceServer>> = _ice.asStateFlow()

    private val iceServers: List<IceServer> get() = _ice.value
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
                runCatching { _ice.value = TrunkWire.parseIce(ch.scry(TrunkWire.AGENT, "/ice")) }
                    .onSuccess { Log.i(TAG, "ice config: ${iceServers.size} servers") }
                    .onFailure { Log.w(TAG, "ice scry failed (Tier 0 only)", it) }
                // Guarded like every other step here. It is internally
                // safe today, but a throw between opening the channel
                // and subscribing is the worst failure this loop has:
                // `channel` is already assigned, so pokes keep working
                // and the ship looks reachable while no fact ever
                // arrives again.
                runCatching { adoptDefaultIce(ch) }
                    .onFailure { Log.w(TAG, "adopting default ice failed", it) }
                // No %trunk (or a desk predating policy) leaves this
                // null, and the settings editor stays hidden.
                runCatching { _policy.value = TrunkWire.parsePolicy(ch.scry(TrunkWire.AGENT, "/policy")) }
                    .onFailure { Log.w(TAG, "policy scry failed; hiding the editor", it) }
                // A ship with no sidecar of its own gets the one this
                // build ships with, so party lines work without any
                // setup. Group admins can point their group elsewhere;
                // a ship that already has an SFU is left alone.
                // A ship running an older wire than we speak is the
                // failure that looks like nothing happening: pokes
                // gall cannot cast, switches that do not move. Say so
                // instead.
                runCatching {
                    val shipWire = TrunkWire.parseWireVersion(
                        ch.scry(TrunkWire.AGENT, "/version"),
                    )
                    _wire.value = shipWire
                    if (shipWire < TrunkWire.WIRE_VERSION &&
                        _install.value == TrunkInstall.Hidden
                    ) {
                        Log.w(TAG, "ship speaks wire $shipWire; we speak ${TrunkWire.WIRE_VERSION}")
                        _install.value =
                            TrunkInstall.Outdated(shipWire, TrunkWire.WIRE_VERSION)
                    }
                }.onFailure { Log.w(TAG, "version scry failed; assuming an old desk", it) }
                runCatching { adoptDefaultSfu(ch) }
                    .onFailure { Log.w(TAG, "default sfu check failed", it) }
                runCatching {
                    _shipSfuBase.value = (ch.scry(TrunkWire.AGENT, "/sfu") as? JsonObject)
                        ?.get("base")?.jsonPrimitive?.content.orEmpty()
                }.onFailure { Log.w(TAG, "sfu scry failed", it) }
                runCatching {
                    val ours = session.shipName.orEmpty()
                    _rooms.value = TrunkWire.parseRooms(ch.scry(TrunkWire.AGENT, "/rooms"))
                        .associateBy { "$ours/${it.name}" }
                }.onFailure { Log.w(TAG, "rooms scry failed", it) }
                runCatching {
                    _invites.value = TrunkWire.parseLines(ch.scry(TrunkWire.AGENT, "/lines"))
                        .associateBy { "${it.host}/${it.name}" }
                }.onFailure { Log.w(TAG, "lines scry failed", it) }
                ch.events().let { events ->
                    ch.subscribe(TrunkWire.AGENT, TrunkWire.CALLS_PATH)
                    backoff = 2_000L
                    events.collect { ev ->
                        ev.id?.let { runCatching { ch.ack(it) } }
                        val body = ev.body as? JsonObject ?: return@collect
                        // Surface poke nacks — a silently-refused poke cost
                        // us a day of "the accept never arrives" debugging.
                        body["err"]?.let { Log.e(TAG, "channel error: $it") }

                        // A kicked or refused subscription is silent
                        // otherwise, and permanent: gall sends %kick,
                        // eyre turns it into {"response":"quit"} with no
                        // error text and no payload, and nothing here or
                        // in the channel layer ever asked again. The
                        // result is a client that pokes fine — so calls
                        // can still be placed — while no ring, accept or
                        // hangup ever arrives, until the app is killed.
                        // Tlon's agents kick subscribers during state
                        // migrations, and a nacked watch lands the same
                        // way, so this has to recover on its own.
                        when (body["response"]?.jsonPrimitive?.contentOrNull) {
                            "quit" -> {
                                Log.w(TAG, "calls subscription was kicked; resubscribing")
                                runCatching { ch.subscribe(TrunkWire.AGENT, TrunkWire.CALLS_PATH) }
                                    .onFailure { Log.e(TAG, "resubscribe failed", it) }
                                return@collect
                            }
                            "subscribe" -> {
                                val err = body["err"]
                                if (err != null && err !is kotlinx.serialization.json.JsonNull) {
                                    // Refused. Retrying the same path in a
                                    // tight loop would spin, so let the
                                    // outer reconnect back off instead.
                                    Log.e(TAG, "calls subscription refused: $err")
                                    throw IllegalStateException("calls watch refused: $err")
                                }
                                return@collect
                            }
                        }
                        val fact = body["json"] ?: return@collect
                        when (val up = TrunkWire.parseUpdate(fact)) {
                            is TrunkUpdate.Recv -> onSignal(up)
                            is TrunkUpdate.Handled -> {
                                // Another of our devices answered or
                                // declined. Stop ringing — but only for
                                // the call we are actually ringing for,
                                // or a stale notice would silence a
                                // genuine incoming call that arrived
                                // since.
                                if (up.callId == callId &&
                                    _state.value is CallUiState.Incoming
                                ) {
                                    Log.i(TAG, "call ${up.callId} handled on another device")
                                    ringToken++
                                    callId = null
                                    peer = null
                                    _state.value = CallUiState.None
                                }
                            }
                            is TrunkUpdate.Ticket -> {
                                // Only the device that asked. A ship is
                                // one identity across many devices and
                                // they all see this fact, so without
                                // this a desktop joining dragged the
                                // phone onto the line too — two
                                // entries for one person, and two
                                // streams the listener has to pick
                                // between.
                                val key = "${up.from}/${up.ticket.name}"
                                if (_pendingJoin.value == key) {
                                    _pendingJoin.value = null
                                    onTicket?.invoke(up.from, up.ticket)
                                } else {
                                    Log.i(TAG, "ignoring ticket for $key; this device didn't ask")
                                }
                            }
                            is TrunkUpdate.Policy -> _policy.value = up.policy
                            // A room of our own changing announces to
                            // us as well, so the switch reflects the
                            // ship rather than a re-scry that races it.
                            is TrunkUpdate.Open -> {
                                val key = "${up.invite.host}/${up.invite.name}"
                                // An announcement answers any peek we had
                                // in flight — and retires the failure
                                // banner a slower earlier attempt left,
                                // which otherwise sat beside a working
                                // line until the chat was reopened.
                                pendingPeeks.update { it - key }
                                _peekFailed.value = _peekFailed.value - key
                                _invites.value = _invites.value + (key to up.invite)
                                if (up.invite.host == session.shipName) refreshRooms()
                            }
                            is TrunkUpdate.Shut -> {
                                _invites.value = _invites.value - "${up.from}/${up.name}"
                                if (up.from == session.shipName) refreshRooms()
                            }
                            is TrunkUpdate.ListenLink ->
                                _listenLink.value = ListenLink(up.room, up.url, up.expiresSecs)
                            is TrunkUpdate.AccessState ->
                                _roomAccess.value = _roomAccess.value +
                                    ("${up.from}/${up.name}" to up.access)
                            is TrunkUpdate.Present ->
                                _presence.value = _presence.value +
                                    ("${up.from}/${up.name}" to up.n)
                            is TrunkUpdate.Recorders ->
                                _recording.value = _recording.value +
                                    ("${up.from}/${up.name}" to up.who)
                            is TrunkUpdate.Denied -> {
                                Log.w(
                                    TAG,
                                    "room " + up.name + " denied by " + up.from + ": " + up.why,
                                )
                                // A relay nack names only the host, not
                                // the room, so an empty name matches any
                                // ask outstanding against that host.
                                fun matches(k: String) = k == "${up.from}/${up.name}" ||
                                    (up.name.isEmpty() && k.startsWith("${up.from}/"))
                                val why = if (up.why == "host unreachable") {
                                    "the host couldn't be reached"
                                } else {
                                    up.why
                                }
                                val peeked = pendingPeeks.value.filter(::matches)
                                if (peeked.isNotEmpty()) {
                                    pendingPeeks.update { it - peeked.toSet() }
                                    // "no such room" answering a peek is
                                    // not a failure — it is the host
                                    // saying this group has no line.
                                    // Every group chat peeks on open, so
                                    // bannering it painted red text over
                                    // healthy line-less groups the moment
                                    // trunk v15 made denials deliverable
                                    // at all. Silence is correct: the
                                    // icon's absence already says it.
                                    if (up.why != "no such room") {
                                        _peekFailed.value =
                                            _peekFailed.value + peeked.associateWith { why }
                                    }
                                }
                                when {
                                    // A denial settles the ask. Leaving
                                    // pendingJoin set meant a grant fact
                                    // fanned out by another device months
                                    // later dragged this one onto the
                                    // line too.
                                    _pendingJoin.value?.let(::matches) == true -> {
                                        // A relay nack names no room, so
                                        // take it from the ask we're
                                        // settling — the banner should
                                        // never name an empty line.
                                        val asked = _pendingJoin.value
                                        _pendingJoin.value = null
                                        val name = up.name.ifEmpty {
                                            asked?.substringAfter('/').orEmpty()
                                        }
                                        onDenied?.invoke(name, why)
                                    }
                                    // A denial that settled a peek stays
                                    // in the peek lane: falling through
                                    // painted the sticky join-refusal
                                    // Failed bar for what was only the
                                    // client's automatic background
                                    // question.
                                    peeked.isNotEmpty() -> {}
                                    up.name.isNotEmpty() -> onDenied?.invoke(up.name, why)
                                    // Nameless, and nothing here asked:
                                    // a host-side relay reflection (one
                                    // unreachable member nacks an
                                    // announce). Not this user's failure
                                    // — no banner.
                                    else -> {}
                                }
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
            if (offerInstallIfMissing()) return@launch
            if (!isFree) return@launch
            val id = Uuid.random().toString()
            callId = id
            peer = target
            tPlaced = nowMs()
            _state.value = CallUiState.Outgoing(target)
            armRingWatchdog()
            // Checked, not fire-and-forget: a ring that never left the
            // device used to play 45s of ringback and then blame the
            // callee with "no answer" for a call they never heard of.
            if (!pokeChecked(target, TrunkSig.Ring(id))) {
                if (callId == id) endLocal("couldn't reach your ship")
                return@launch
            }
            // The ack wait above can outlive the call (hangup, glare
            // adoption). A superseded coroutine must not open a mic —
            // and must close the one it opened, because endLocal has
            // already run for this call and will not run again.
            if (callId != id) return@launch
            // Gather while the far end rings (design D3: the slow parts overlap).
            val eng = engineProvider.create(iceServers)
            if (callId != id) {
                eng.close()
                return@launch
            }
            engine = eng
            watchMedia(eng, target)
            runCatching { eng.createOffer() }
                .onSuccess { offer ->
                    // Still ours? A hangup during gathering (or a glare
                    // tie-break) supersedes this coroutine, and its
                    // stale offer must not poke — or worse, its stale
                    // failure must not end the NEXT call. The desktop
                    // engine makes that real: close() mid-gather leaves
                    // createOffer to throw a full 8s later.
                    if (callId != id) return@onSuccess
                    tOfferSent = nowMs()
                    Log.i(TAG, "Trunk metric: gather took ${tOfferSent - tPlaced}ms")
                    // A lost offer strands the callee ringing toward
                    // "no offer arrived" — end honestly instead.
                    if (!pokeChecked(target, TrunkSig.Offer(id, offer.sdp, offer.fingerprint)) &&
                        callId == id
                    ) {
                        endLocal("couldn't reach your ship")
                        poke(target, TrunkSig.Hangup(id))
                    }
                }
                .onFailure {
                    if (callId != id) return@onFailure
                    Log.e(TAG, "offer failed", it)
                    // Locally first — same rule as hangup(): the state
                    // change shouldn't wait on the poke's ack.
                    endLocal(it.message ?: "media error")
                    poke(target, TrunkSig.Hangup(id))
                }
        }
    }

    /** Answer the ringing call. [forCallId] pins which call the caller
     *  of this function meant — a notification action can outlive the
     *  ring it was posted for, and answering whatever rings *now*
     *  would accept a different caller. Null answers unconditionally
     *  (the in-app button, which renders live state). */
    fun accept(forCallId: String? = null) {
        scope.launch {
            val id = callId ?: return@launch
            if (forCallId != null && forCallId != id) {
                Log.i(TAG, "stale accept for $forCallId; ringing call is $id")
                return@launch
            }
            ringToken++ // answered: stop the give-up timer
            val from = peer ?: return@launch
            _state.value = CallUiState.Active(from, MediaState.Connecting, muted = false)
            armConnectWatchdog()
            val offer = runCatching {
                kotlinx.coroutines.withTimeout(20_000) { pendingOffer.await() }
            }.getOrElse {
                if (callId != id) return@launch
                Log.e(TAG, "no offer within 20s of answering")
                endLocal("no offer arrived")
                poke(from, TrunkSig.Hangup(id))
                return@launch
            }
            // The offer wait above is up to 20s, and Android's engine
            // provider can itself block on the mic-permission prompt —
            // the call may be long gone when either returns. A stale
            // engine here is a hot mic nothing will ever close.
            if (callId != id) return@launch
            val eng = engineProvider.create(iceServers)
            if (callId != id) {
                eng.close()
                return@launch
            }
            engine = eng
            // A mute tapped while "Connecting…" hit a null engine and
            // only flipped the UI flag, so the strip showed MicOff with
            // a live mic. The strip is interactive for the whole offer
            // wait, so re-apply whatever it is showing.
            (_state.value as? CallUiState.Active)?.let { eng.setMuted(it.muted) }
            watchMedia(eng, from)
            runCatching { eng.acceptOffer(offer) }
                .onSuccess { answer ->
                    if (callId != id) return@onSuccess
                    if (!pokeChecked(from, TrunkSig.Accept(id, answer.sdp, answer.fingerprint)) &&
                        callId == id
                    ) {
                        endLocal("couldn't reach your ship")
                        poke(from, TrunkSig.Hangup(id))
                    }
                }
                .onFailure {
                    if (callId != id) return@onFailure
                    Log.e(TAG, "answer failed", it)
                    endLocal(it.message ?: "media error")
                    poke(from, TrunkSig.Hangup(id))
                }
        }
    }

    fun reject() {
        scope.launch {
            val id = callId ?: return@launch
            val p = peer
            endLocal("declined")
            p?.let { poke(it, TrunkSig.Reject(id, "declined")) }
        }
    }

    fun hangup() {
        scope.launch {
            val id = callId ?: return@launch
            val p = peer
            // End locally FIRST, then tell the far end. A poke now
            // waits for its ack (up to POKE_ACK_TIMEOUT_MS), so doing
            // this the other way round left the call on screen — and
            // the red button looking dead — for as long as the peer's
            // ship took to answer, or the full timeout if it never
            // did. Hanging up is a local decision; it doesn't need
            // anyone's permission.
            endLocal("hung up")
            p?.let { poke(it, TrunkSig.Hangup(id)) }
        }
    }

    /**
     * The live call's engine, or null between calls.
     *
     * Exposed only so the video surface can reach the platform's own
     * track — it is the one thing that cannot cross into commonMain.
     * Everything else about a call still goes through this controller.
     */
    val mediaEngine: CallEngine? get() = engine

    /**
     * Turn our camera on or off for the live call.
     *
     * No signalling: the video transceiver was negotiated in the first
     * offer, so this is a local track swap (see
     * [CallEngine.setCameraEnabled]). Silent when there is no call.
     */
    fun setCamera(on: Boolean) {
        val eng = engine ?: return
        scope.launch {
            if (!eng.setCameraEnabled(on) && on) {
                Log.w(TAG, "the camera would not start")
            }
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
    /** (host, ticket): the host is needed to find the room the
     *  ticket belongs to, which is where the topic lives. */
    /** Ask the host how many are on [host]'s line [name] right now.
     *  The answer lands in [presence]. Fire-and-forget; a wire-5 host
     *  just nacks and presence stays absent. */
    suspend fun occupancyOf(host: String, name: String) {
        val ch = channel ?: return
        runCatching { ch.poke(TrunkWire.AGENT, TrunkWire.ACTION_MARK, TrunkWire.occupancyOfAction(host, name)) }
            .onFailure { Log.i(TAG, "occupancy-of declined (older host?): ${it.message}") }
    }

    /** Tell [host] we connected to / left its line [name]. enterRoom
     *  doubles as a heartbeat; the host ages us out if we stop. */
    suspend fun enterRoom(host: String, name: String) {
        val ch = channel ?: return
        runCatching { ch.poke(TrunkWire.AGENT, TrunkWire.ACTION_MARK, TrunkWire.enterRoomAction(host, name)) }
            .onFailure { Log.i(TAG, "enter-room declined: ${it.message}") }
    }

    suspend fun leaveRoom(host: String, name: String) {
        val ch = channel ?: return
        runCatching { ch.poke(TrunkWire.AGENT, TrunkWire.ACTION_MARK, TrunkWire.leaveRoomAction(host, name)) }
            .onFailure { Log.i(TAG, "leave-room declined: ${it.message}") }
    }

    /** Tell [host] we started / stopped recording its line [name].
     *  startRecording doubles as a heartbeat; the host ages us out of
     *  the recorder set if we stop pinging. Fire-and-forget; a pre-
     *  wire-7 host just nacks and no badge appears. */
    suspend fun startRecording(host: String, name: String) {
        val ch = channel ?: return
        runCatching { ch.poke(TrunkWire.AGENT, TrunkWire.ACTION_MARK, TrunkWire.startRecordingAction(host, name)) }
            .onFailure { Log.i(TAG, "start-recording declined (older host?): ${it.message}") }
    }

    /**
     * Announce that we are recording (host, name) and keep saying so
     * until [endRecordingAnnounce].
     *
     * The heartbeat lives here, on the controller's own scope, rather
     * than in the screen that started it. Two bugs came from having it
     * in the chat slot: opening a DM disposed the effect, whose `finally`
     * told the room recording had stopped while the taps kept running,
     * and the room it announced to was whichever chat happened to be
     * open rather than the line being recorded. Both are captured here
     * once, at the moment recording starts.
     */
    fun beginRecordingAnnounce(host: String, name: String) {
        recAnnounce?.cancel()
        recAnnounce = scope.launch {
            try {
                while (true) {
                    startRecording(host, name)
                    delay(RECORD_HEARTBEAT_MS)
                }
            } finally {
                withContext(NonCancellable) { stopRecording(host, name) }
            }
        }
    }

    /** Stop announcing, and tell the room we stopped. */
    fun endRecordingAnnounce() {
        recAnnounce?.cancel()
        recAnnounce = null
    }

    /**
     * Tell the host we're on its line (host, name), keep saying so
     * until [line] ends, then tell it we left.
     *
     * Same lesson as [beginRecordingAnnounce]: this used to be an
     * effect in the chat slot keyed on the open group, so walking to
     * another group while still talking cancelled it, its `finally`
     * sent %left, and everyone's "N on the line" badge dropped by one
     * for someone who was still there. The line's own state is the
     * only thing that says whether we're on it. The occupancy ask
     * after each enter and the final leave is what lets our own badge
     * catch up within a second instead of at the next 20s poll.
     */
    fun beginPresenceAnnounce(host: String, name: String, line: StateFlow<PartyState>) {
        presenceAnnounce?.cancel()
        presenceAnnounce = scope.launch {
            try {
                val beat = launch {
                    while (true) {
                        enterRoom(host, name) // doubles as heartbeat
                        occupancyOf(host, name)
                        delay(PRESENCE_HEARTBEAT_MS)
                    }
                }
                line.first { it is PartyState.Idle || it is PartyState.Failed }
                beat.cancel()
            } finally {
                withContext(NonCancellable) {
                    leaveRoom(host, name)
                    occupancyOf(host, name)
                }
            }
        }
    }

    suspend fun stopRecording(host: String, name: String) {
        val ch = channel ?: return
        runCatching { ch.poke(TrunkWire.AGENT, TrunkWire.ACTION_MARK, TrunkWire.stopRecordingAction(host, name)) }
            .onFailure { Log.i(TAG, "stop-recording declined: ${it.message}") }
    }

    /** Ask the host who is recording [host]'s line [name] right now.
     *  The answer lands in [recording]. */
    suspend fun recordersOf(host: String, name: String) {
        val ch = channel ?: return
        runCatching { ch.poke(TrunkWire.AGENT, TrunkWire.ACTION_MARK, TrunkWire.recordersOfAction(host, name)) }
            .onFailure { Log.i(TAG, "recorders-of declined (older host?): ${it.message}") }
    }

    var onTicket: ((String, TrunkTicket) -> Unit)? = null

    /** A host refused us a line. Surfaced so tapping the button always
     *  says something — silence reads as a broken button. */
    var onDenied: ((name: String, why: String) -> Unit)? = null

    /** Host a party line: [members] are the ships allowed to join and
     *  [admins] those allowed to reconfigure it. The agent announces
     *  it to each member. */
    suspend fun openRoom(
        name: String,
        title: String,
        members: List<String>,
        admins: List<String> = emptyList(),
    ) {
        val ch = channel ?: return
        runCatching {
            ch.poke(
                TrunkWire.AGENT, TrunkWire.ACTION_MARK,
                TrunkWire.openRoomAction(name, title, members, admins),
            )
        }.onFailure { Log.e(TAG, "open-room poke failed", it) }
    }

    /**
     * Reconfigure a line hosted by [host]. Works whether or not we are
     * the host: our ship relays it, and the host enforces membership
     * of its admin list. Setting [open] false closes the line.
     */
    suspend fun configureRoom(
        host: String,
        name: String,
        open: Boolean,
        listen: Boolean,
        sfu: SfuConfig? = null,
        keepSfu: Boolean = true,
        title: String = "",
        members: List<String> = emptyList(),
        admins: List<String> = emptyList(),
    ) {
        val ch = channel ?: return
        runCatching {
            ch.poke(
                TrunkWire.AGENT, TrunkWire.ACTION_MARK,
                TrunkWire.configureRoomAction(
                    host, name, open, listen, sfu, keepSfu, title, members, admins,
                ),
            )
        }.onFailure { Log.e(TAG, "configure-room poke failed", it) }
        // The host answers with an announcement; refresh our own view
        // too, for the case where the host is us.
        refreshRooms()
    }

    /**
     * Point this ship at the build's default sidecar, but only if it
     * has none. Never overwrites a ship that has been configured — a
     * user who set their own server keeps it.
     */
    private suspend fun adoptDefaultSfu(ch: UrbitChannel) {
        if (defaults.sfuBase.isEmpty()) return
        val configured = runCatching {
            (ch.scry(TrunkWire.AGENT, "/sfu") as? JsonObject)
                ?.get("configured")?.jsonPrimitive?.content == "true"
        }.getOrElse { return }
        if (configured) return
        Log.i(TAG, "no sidecar on this ship; adopting the built-in default")
        _shipSfuBase.value = defaults.sfuBase
        runCatching {
            ch.poke(
                TrunkWire.AGENT, TrunkWire.ACTION_MARK,
                TrunkWire.setSfuAction(
                    defaults.sfuBase,
                    defaults.sfuGroup,
                    defaults.sfuKey,
                ),
            )
        }.onFailure { Log.w(TAG, "set-sfu poke failed", it) }
    }

    /**
     * Point this ship at the build's default STUN/TURN, but only if
     * it has none. Same contract as [adoptDefaultSfu]: a ship someone
     * configured keeps what they chose.
     *
     * Writing it to the ship rather than falling back locally is
     * deliberate — `/x/ice` is then right for every device signed
     * into this ship and for any other app sharing %trunk, and the
     * settings editor has one place to read and write.
     */
    private suspend fun adoptDefaultIce(ch: UrbitChannel) {
        if (iceServers.isNotEmpty()) return
        val fallback = TrunkWire.defaultIce(defaults)
        if (fallback.isEmpty()) return
        Log.i(TAG, "no ICE on this ship; adopting ${fallback.size} built-in servers")
        runCatching {
            ch.poke(TrunkWire.AGENT, TrunkWire.ACTION_MARK, TrunkWire.setIceAction(fallback))
        }.onFailure {
            Log.w(TAG, "set-ice poke failed", it)
            return
        }
        // Only trust it once the ship confirms; a nacked poke that
        // still updated the flow would hide a broken desk behind a
        // settings screen that looks configured.
        runCatching { _ice.value = TrunkWire.parseIce(ch.scry(TrunkWire.AGENT, "/ice")) }
            .onFailure { Log.w(TAG, "ice re-scry after adopt failed", it) }
    }

    /**
     * Replace this ship's ICE servers. An empty list clears them, and
     * the build default is adopted again on the next connect.
     */
    suspend fun setIce(servers: List<IceServer>) {
        val ch = channel ?: return
        runCatching {
            ch.poke(TrunkWire.AGENT, TrunkWire.ACTION_MARK, TrunkWire.setIceAction(servers))
        }.onFailure {
            Log.e(TAG, "set-ice poke failed", it)
            return
        }
        runCatching { _ice.value = TrunkWire.parseIce(ch.scry(TrunkWire.AGENT, "/ice")) }
            .onFailure { Log.w(TAG, "ice re-scry failed", it) }
    }

    /** Re-read the lines this ship hosts. */
    suspend fun refreshRooms() {
        val ch = channel ?: return
        val ours = session.shipName.orEmpty()
        runCatching {
            _rooms.value = TrunkWire.parseRooms(ch.scry(TrunkWire.AGENT, "/rooms"))
                .associateBy { "$ours/${it.name}" }
        }.onFailure { Log.w(TAG, "rooms refresh failed", it) }
    }

    /** Turn anonymous listening on or off for a room we host. */
    suspend fun setRoomListen(name: String, listen: Boolean) {
        val ch = channel ?: return
        runCatching {
            ch.poke(
                TrunkWire.AGENT, TrunkWire.ACTION_MARK,
                TrunkWire.setRoomListenAction(name, listen),
            )
        }.onFailure { Log.e(TAG, "set-room-listen poke failed", it) }
    }

    /**
     * Ask for an anonymous listen link. The ship answers with a
     * %listen-link fact on [listenLink] — and answers with nothing at
     * all if the room's admins haven't enabled listening, which is the
     * refusal, not an error.
     */
    suspend fun shareRoom(
        host: String,
        name: String,
        ttlSecs: Int = DEFAULT_LISTEN_TTL_SECS,
    ) {
        val ch = channel ?: return
        runCatching {
            ch.poke(
                TrunkWire.AGENT, TrunkWire.ACTION_MARK,
                TrunkWire.shareRoomAction(host, name, ttlSecs),
            )
        }.onFailure { Log.e(TAG, "share-room poke failed", it) }
    }

    /**
     * True when we asked the user to install %trunk instead of doing
     * what they wanted. Checked before placing a call or joining a
     * line — the two things that need the desk.
     */
    private fun offerInstallIfMissing(): Boolean {
        if (!trunkMissing) return false
        if (_install.value == TrunkInstall.Hidden) _install.value = TrunkInstall.Offered
        return true
    }

    fun dismissInstall() { _install.value = TrunkInstall.Hidden }

    /**
     * Install %trunk from the publisher. The poke returns as soon as
     * %kiln accepts it, but the desk arrives over ames afterwards, so
     * success means "the agent answers a scry", not "the poke acked".
     */
    fun installTrunk(publisher: String = TrunkWire.PUBLISHER) {
        if (_install.value == TrunkInstall.Installing) return
        _install.value = TrunkInstall.Installing
        scope.launch {
            val ch = channel
            if (ch == null) {
                _install.value = TrunkInstall.Failed("not connected to your ship")
                return@launch
            }
            val (app, mark, body) = TrunkWire.installTrunkPoke(publisher)
            val poked = runCatching { ch.poke(app, mark, body) }
                .onFailure { Log.e(TAG, "kiln-install poke failed", it) }
                .isSuccess
            if (!poked) {
                _install.value = TrunkInstall.Failed("your ship refused the install")
                return@launch
            }
            // Poll rather than guess a duration: a desk can take a
            // while to come over ames, and there is no fact to await.
            val started = nowMs()
            val deadline = started + INSTALL_TIMEOUT_MS
            var revived = false
            while (nowMs() < deadline) {
                delay(3_000)
                // A ship that previously removed %trunk keeps the desk
                // suspended, and installing re-syncs it without
                // starting it. Nudge it once, halfway in, rather than
                // waiting out the whole timeout for nothing.
                if (!revived && nowMs() - started > INSTALL_REVIVE_AFTER_MS) {
                    revived = true
                    val (rApp, rMark, rBody) = TrunkWire.reviveTrunkPoke()
                    runCatching { ch.poke(rApp, rMark, rBody) }
                        .onFailure { Log.i(TAG, "revive poke declined (usually fine): " + it.message) }
                }
                val got = runCatching {
                    TrunkWire.parsePolicy(ch.scry(TrunkWire.AGENT, "/policy"))
                }.getOrNull()
                if (got != null) {
                    _policy.value = got
                    _install.value = TrunkInstall.Hidden
                    Log.i(TAG, "%trunk installed from " + publisher)
                    return@launch
                }
            }
            // Deliberately not "check your ship can reach X". A poke is
            // fire-and-forget over the channel — we never see its ack —
            // so this branch cannot distinguish a refused install from
            // a slow one, and it used to assert the one diagnosis we
            // have no evidence for.
            _install.value = TrunkInstall.Failed(
                "%trunk hasn't arrived from $publisher yet. It may still be " +
                    "on its way — check with |vats %trunk in the dojo.",
            )
        }
    }

    /**
     * Change who may ring us. Each edit is a poke; the agent echoes the
     * whole policy back on /calls, so [policy] updates from the fact
     * rather than optimistically here — that keeps every device on the
     * ship showing the same thing.
     */
    suspend fun setCallMode(mode: CallPolicy.Mode) =
        pokePolicy("set-call-mode", TrunkWire.setCallModeAction(mode))

    suspend fun setAllowed(peer: String, allowed: Boolean) =
        pokePolicy("allow", TrunkWire.allowAction(peer, allowed))

    suspend fun setBlocked(peer: String, blocked: Boolean) =
        pokePolicy("block", TrunkWire.blockAction(peer, blocked))

    private suspend fun pokePolicy(what: String, action: kotlinx.serialization.json.JsonElement) {
        val ch = channel ?: return
        runCatching { ch.poke(TrunkWire.AGENT, TrunkWire.ACTION_MARK, action) }
            .onFailure { Log.e(TAG, "$what poke failed", it) }
    }

    /**
     * Ask [host] whether it hosts a line called [name].
     *
     * Cheap and idempotent: the host answers with the announcement a
     * member would have received, which lands in [invites] exactly as
     * a pushed one does, or with a denial that costs nothing. Safe to
     * fire on opening a group — it mints no token and joins nothing.
     */
    /**
     * Hosts we asked and could not reach, by "host/name".
     *
     * A peek needs wire 2 on *both* ships: ours to send it, and the
     * host's to understand it. An older host receives a room-sig
     * carrying a variant its type has no case for, the mark fails to
     * cast, and the poke nacks — so the answer never comes and the
     * line stays invisible with nothing to explain why. This is that
     * explanation, kept so the UI can say it.
     */
    private val _peekFailed = MutableStateFlow<Map<String, String>>(emptyMap())
    val peekFailed: StateFlow<Map<String, String>> = _peekFailed.asStateFlow()

    suspend fun peekRoom(host: String, name: String) {
        val ch = channel ?: return
        val key = "$host/$name"
        try {
            pendingPeeks.update { it + key }
            ch.poke(TrunkWire.AGENT, TrunkWire.ACTION_MARK, TrunkWire.peekRoomAction(host, name))
            _peekFailed.value = _peekFailed.value - key
        } catch (t: kotlinx.coroutines.CancellationException) {
            pendingPeeks.update { it - key }
            // Navigating away while the ack is in flight cancels this
            // coroutine — it says nothing about the host. Recording it
            // was the bug users saw as a permanent "Party line: The
            // coroutine scope left the composition" banner: peekFailed
            // outlives the screen, so the internal message came back
            // every time the chat reopened.
            throw t
        } catch (t: Throwable) {
            // A nack here is information, not noise: our own ship
            // refusing the action means our desk is too old, and the
            // host refusing means theirs is. But the banner gets a
            // sentence we wrote, never t.message — a Ktor timeout's
            // message is a URL and a config dump, and rc31 shipped it
            // verbatim above people's pinned messages.
            // This poke goes to our OWN ship — the host only hears
            // about it later, over ames. So a nack here is our desk
            // refusing the action (bad-key = it predates %peek-room),
            // never the host's answer; blaming "$host is running an
            // older Trunk" pointed users at the one ship that had
            // done nothing wrong. The host's side arrives async as an
            // %open or %denied fact and is handled there.
            pendingPeeks.update { it - key }
            val why = when {
                t is PokeNacked && t.reason.contains("bad-key") ->
                    "your ship's Trunk is too old to ask — update %trunk"
                t is PokeNacked -> "your ship declined to ask"
                else -> "your ship didn't answer in time"
            }
            Log.w(TAG, "peek $key failed: $why (${t.message})")
            _peekFailed.value = _peekFailed.value + (key to why)
        }
    }

    /**
     * Role gates per room, keyed "~host/name", fed by %access-state
     * facts — the host's answer to every one of the three role
     * actions below. Empty until [getRoomAccess] (or an edit) asks.
     */
    private val _roomAccess = MutableStateFlow<Map<String, RoomAccess>>(emptyMap())
    val roomAccess: StateFlow<Map<String, RoomAccess>> = _roomAccess.asStateFlow()

    /** Live occupancy per line, keyed "~host/name" — how many are on
     *  the line right now, for the "N on the line" chip a non-joined
     *  viewer sees. Fed by %present facts (wire 6). */
    private val _presence = MutableStateFlow<Map<String, Int>>(emptyMap())
    val presence: StateFlow<Map<String, Int>> = _presence.asStateFlow()

    /** Per line ("~host/name"), the ships recording it right now, for
     *  the recording badge every member on the line sees. Fed by
     *  %recorders facts (wire 7). */
    /** The live recording-announce heartbeat, if we are recording. */
    private var recAnnounce: Job? = null
    /** The live presence heartbeat, while we are on a line. */
    private var presenceAnnounce: Job? = null

    private val _recording = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val recording: StateFlow<Map<String, Set<String>>> = _recording.asStateFlow()

    /**
     * Why the last role action failed, or null. Only the wire-5 role
     * pokes write it: a host desk that predates roles can't cast the
     * action mark and nacks, and the admin screen shows this instead
     * of a switch that silently snaps back. Cleared by
     * [dismissRoleError] and by the next role poke that succeeds.
     */
    private val _roleError = MutableStateFlow<String?>(null)
    val roleError: StateFlow<String?> = _roleError.asStateFlow()

    fun dismissRoleError() { _roleError.value = null }

    /** Set who may join / speak on [host]'s line. Null = everyone.
     *  The host answers with an %access-state fact → [roomAccess]. */
    suspend fun setRoomAccess(
        host: String,
        name: String,
        joinRoles: List<String>?,
        speakRoles: List<String>?,
    ) = pokeRoles(
        "set-room-access",
        TrunkWire.setRoomAccessAction(host, name, joinRoles, speakRoles),
    )

    /** Mute (or unmute) [who] on [host]'s line — moderation. */
    suspend fun moderateMember(host: String, name: String, who: String, mute: Boolean) =
        pokeRoles("moderate-member", TrunkWire.moderateMemberAction(host, name, who, mute))

    /** Ask [host] for a room's gates; the answer lands in [roomAccess]. */
    suspend fun getRoomAccess(host: String, name: String) =
        pokeRoles("get-room-access", TrunkWire.getRoomAccessAction(host, name))

    private suspend fun pokeRoles(what: String, action: kotlinx.serialization.json.JsonElement) {
        val ch = channel
        if (ch == null) {
            // A silent no-op here read as "saved" in the admin UI.
            _roleError.value = "not connected to your ship"
            return
        }
        try {
            ch.poke(TrunkWire.AGENT, TrunkWire.ACTION_MARK, action)
            _roleError.value = null
        } catch (t: kotlinx.coroutines.CancellationException) {
            // Navigating away mid-ack says nothing about the host.
            throw t
        } catch (t: Throwable) {
            Log.e(TAG, "$what poke failed", t)
            _roleError.value = if (t is PokeNacked) {
                // Our own ship acks these pokes; a remote wire-4 host
                // just drops the sig, which no ack ever reports.
                "your ship's %trunk doesn't support roles yet"
            } else {
                "your ship didn't answer"
            }
        }
    }

    /**
     * Bind a room we host to a Tlon group, so its roster mirrors the
     * group's from now on. Wire 4+; callers gate on [wire].
     */
    suspend fun bindRoom(name: String, groupFlag: String?) {
        val ch = channel ?: return
        runCatching {
            ch.poke(
                TrunkWire.AGENT, TrunkWire.ACTION_MARK,
                TrunkWire.bindRoomAction(name, groupFlag),
            )
        }.onFailure { Log.w(TAG, "bind-room poke failed", it) }
    }

    /** Ask [host] to let us onto its party line. */
    suspend fun joinRoom(host: String, name: String) {
        if (offerInstallIfMissing()) return
        val ch = channel ?: return
        // Claim the answer before asking: the grant comes back as a
        // fact every device of this ship can see.
        val key = "$host/$name"
        val token = ++joinToken
        _pendingJoin.value = key
        runCatching {
            ch.poke(
                TrunkWire.AGENT, TrunkWire.ACTION_MARK,
                TrunkWire.joinRoomAction(host, name),
            )
        }.onFailure {
            // Never asked; nothing can ever answer.
            _pendingJoin.value = null
            Log.e(TAG, "join-room poke failed", it)
            onDenied?.invoke(name, "your ship declined to ask")
        }
        // The grant rides ames; a sleeping host answers in seconds to
        // never. Give the ask a deadline so the pending indicator
        // can't sit forever, and so a grant provoked much later (by
        // another device's ask) can't drag this one onto the line.
        scope.launch {
            delay(joinAskTimeoutMs)
            if (joinToken == token && _pendingJoin.value == key) {
                _pendingJoin.value = null
                onDenied?.invoke(name, "the host didn't answer")
            }
        }
    }

    /** Withdraw a pending ask — the bar's cancel while "asking…". The
     *  grant may still arrive; with the claim cleared it is ignored,
     *  exactly like a grant another device asked for. */
    fun cancelJoin() {
        joinToken++
        _pendingJoin.value = null
    }

    /** Disarms a superseded ask's deadline, ringToken-style. */
    private var joinToken = 0

    private suspend fun onSignal(recv: TrunkUpdate.Recv) {
        val sig = recv.sig
        when (sig) {
            is TrunkSig.Ring -> {
                // Glare: a ring FROM the ship we are currently dialing
                // is unambiguous mutual intent, not a busy signal.
                // Ignoring it (as any other mid-call ring is ignored)
                // meant two people dialing each other both sat through
                // the full ring timeout and both got "no answer". Tie-
                // break deterministically: the lexicographically lower
                // ship abandons its own attempt and takes the incoming
                // ring; the higher one keeps ringing and is answered.
                val cur = _state.value
                if (cur is CallUiState.Outgoing && recv.from == peer) {
                    val ours = session.shipName.orEmpty()
                    if (ours >= recv.from) return
                    Log.i(TAG, "glare with ${recv.from}; adopting their ring")
                    val oldId = callId
                    connectToken++
                    mediaWatch?.cancel()
                    mediaWatch = null
                    engine?.close()
                    engine = null
                    // Their other devices may be ringing for our
                    // abandoned call — tell them it's over.
                    oldId?.let { old ->
                        scope.launch { poke(recv.from, TrunkSig.Hangup(old)) }
                    }
                    callId = sig.id
                    pendingOffer = CompletableDeferred()
                    _state.value = CallUiState.Incoming(recv.from)
                    armRingWatchdog()
                    return
                }
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
                // Only the first accept transitions the call. Every
                // callee device rings and the handled-elsewhere
                // suppression is asynchronous, so two of them answering
                // within an ames round trip sends two accepts with the
                // same id — and the second used to knock a live call
                // back to Connecting and then drop it when libwebrtc
                // refused the duplicate remote description.
                if (_state.value !is CallUiState.Outgoing) {
                    Log.i(TAG, "duplicate accept for ${sig.id} ignored")
                    return
                }
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
                // An old desk's relay nack can't echo the call id — it
                // wasn't in the wire — so it rejects as id "unknown".
                // Coming from the ship we are calling, that can only
                // mean our signaling never got through (no %trunk, or
                // unreachable); dropping it left the caller hearing
                // ringback for the full timeout and then a "no answer"
                // that blamed the callee.
                val unreachable = sig.id == "unknown" && recv.from == peer &&
                    (_state.value is CallUiState.Outgoing || _state.value is CallUiState.Active)
                if (sig.id != callId && !unreachable) return
                endLocal(
                    when {
                        sig.reason == "unreachable" || unreachable -> "couldn't be reached"
                        sig.reason == "busy" -> "busy"
                        else -> "declined"
                    },
                )
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
                Log.w(TAG, "media never connected after ${connectTimeoutMs}ms")
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
                    MediaState.Failed -> {
                        // Before the call is Active, the offer/answer
                        // path is mid-flight and fails with the
                        // engine's own message — the actionable one
                        // (the unavailable-engine says how to fix the
                        // mic). Ending here first replaced it with a
                        // generic "connection failed".
                        if (_state.value is CallUiState.Active) endCall("connection failed")
                    }
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
        // A call can end down more than one path at once — an engine
        // failure racing its own cleanup poke. The first arrival wins;
        // a second must not downgrade the Ended banner (and its
        // reason) to None.
        val cur = _state.value
        if (callId == null && peer == null &&
            (cur is CallUiState.None || cur is CallUiState.Ended)
        ) {
            // Still sweep the engine: a superseded coroutine may have
            // parked one after its call already ended, and this guard
            // must never turn that into a permanently hot mic.
            mediaWatch?.cancel()
            mediaWatch = null
            engine?.close()
            engine = null
            return
        }
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
                    Log.i(TAG, "no answer after ${ringTimeoutMs}ms")
                    // Locally first, poke second — same rule as
                    // hangup(): waiting on the poke's ack kept the
                    // ringback going up to 15s past the timeout on a
                    // connection whose ack path had died. id and
                    // target were captured when the watchdog was
                    // armed, so ending first loses nothing.
                    endLocal("no answer")
                    if (id != null && target != null) poke(target, TrunkSig.Hangup(id))
                }
                is CallUiState.Incoming -> {
                    Log.i(TAG, "missed call from $target")
                    endLocal("missed")
                }
                else -> {}
            }
        }
    }

    private suspend fun poke(target: String, sig: TrunkSig) {
        pokeChecked(target, sig)
    }

    /** Like [poke], but the caller learns whether it left the device.
     *  Ring/Offer/Accept need to know — a signal that never went out
     *  must end the call now, not after a watchdog blames the peer.
     *  Hangup/Reject stay fire-and-forget: the call is already over
     *  locally and the peer's own watchdog covers the loss. */
    private suspend fun pokeChecked(target: String, sig: TrunkSig): Boolean {
        val ch = channel ?: return false
        return runCatching {
            ch.poke(TrunkWire.AGENT, TrunkWire.ACTION_MARK, TrunkWire.sendAction(target, sig))
        }.onFailure { Log.e(TAG, "poke ${sig::class.simpleName} failed", it) }.isSuccess
    }

    companion object {
        private const val TAG = "Trunk"

        /** Default life of a listen link. Short on purpose: the link
         *  is a bearer token nothing can revoke, so its lifetime is
         *  the only brake. The ship caps it regardless. */
        const val DEFAULT_LISTEN_TTL_SECS = 900

        /** How long to wait for an installed desk to start answering. */
        /**
         * How long to watch for an installing desk.
         *
         * Was two minutes, which is simply less than a cold desk sync
         * from a foreign ship takes over ames — reported as an install
         * that "failed" twice and then worked from the dojo, because
         * by then most of the sync our two attempts started had
         * already happened.
         *
         * Long is cheap now that the dialog can be closed: the poll
         * runs on the controller's scope, not the composition.
         */
        /** Silence between ringback bursts, as a phone network does. */
        private const val RINGBACK_GAP_MS = 3_000
        /** Shorter for an incoming call: it is asking for attention. */
        private const val INCOMING_GAP_MS = 1_400

        private const val INSTALL_TIMEOUT_MS = 600_000L

        /** Nudge a suspended desk early rather than at half of ten
         *  minutes, which would be far too late to help. */
        private const val INSTALL_REVIVE_AFTER_MS = 45_000L

        /** How long a caller rings before giving up. Public because
         *  Android's ring notification has to expire no later than
         *  this — see Notifications.showIncomingCall. */
        const val DEFAULT_RING_TIMEOUT_MS = 45_000L

        /** How long a party-line ask may sit unanswered before the
         *  pending indicator gives up on the host. */
        internal const val JOIN_ASK_TIMEOUT_MS = 15_000L

        /** How long the "call ended" notice lingers before the surface
         *  goes quiet. Cleared here, not in the UI, so a backgrounded
         *  app still frees itself to take the next call. */
        private const val ENDED_NOTICE_MS = 5_000L

        /** How often we re-announce an active recording. The host ages a
         *  recorder out after 90s, so this has to stay well under it. */
        private const val RECORD_HEARTBEAT_MS = 30_000L
        private const val PRESENCE_HEARTBEAT_MS = 30_000L
    }
}
