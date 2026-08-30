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

    /** "host/room" this device asked to join, or null. */
    private var pendingJoin: String? = null

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
                                if (pendingJoin == key) {
                                    pendingJoin = null
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
                                _invites.value = _invites.value +
                                    ("${up.invite.host}/${up.invite.name}" to up.invite)
                                if (up.invite.host == session.shipName) refreshRooms()
                            }
                            is TrunkUpdate.Shut -> {
                                _invites.value = _invites.value - "${up.from}/${up.name}"
                                if (up.from == session.shipName) refreshRooms()
                            }
                            is TrunkUpdate.ListenLink ->
                                _listenLink.value = ListenLink(up.room, up.url, up.expiresSecs)
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
            if (offerInstallIfMissing()) return@launch
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
        runCatching {
            ch.poke(TrunkWire.AGENT, TrunkWire.ACTION_MARK, TrunkWire.peekRoomAction(host, name))
        }.onSuccess {
            _peekFailed.value = _peekFailed.value - key
        }.onFailure { t ->
            // A nack here is information, not noise: our own ship
            // refusing the action means our desk is too old, and the
            // host refusing means theirs is.
            val why = when {
                t is PokeNacked && t.reason.contains("bad-key") ->
                    "$host is running an older Trunk that can't answer this yet"
                else -> t.message ?: "the host didn't answer"
            }
            Log.w(TAG, "peek $key failed: $why")
            _peekFailed.value = _peekFailed.value + (key to why)
        }
    }

    /** Ask [host] to let us onto its party line. */
    suspend fun joinRoom(host: String, name: String) {
        if (offerInstallIfMissing()) return
        val ch = channel ?: return
        // Claim the answer before asking: the grant comes back as a
        // fact every device of this ship can see.
        pendingJoin = "$host/$name"
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

        /** How long the "call ended" notice lingers before the surface
         *  goes quiet. Cleared here, not in the UI, so a backgrounded
         *  app still frees itself to take the next call. */
        private const val ENDED_NOTICE_MS = 5_000L
    }
}
