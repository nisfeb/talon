package io.nisfeb.talon.call

import io.ktor.client.HttpClient
import kotlin.concurrent.Volatile
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.nisfeb.talon.util.Log
import io.nisfeb.talon.util.backgroundExceptionHandler
import io.nisfeb.talon.util.ioDispatcher
import io.nisfeb.talon.util.nowMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Who is on the line right now, as the SFU reports it.
 *
 * [speaking] is a live read of the person's audio level, so a roster
 * can show who is actually talking rather than only who is present.
 */
data class PartyMember(
    val id: String,
    val ship: String,
    val speaking: Boolean = false,
    /**
     * Whether this person has their mic off.
     *
     * Not something the SFU knows — muting is a local track disable, so
     * to Galène a muted speaker is indistinguishable from a silent one.
     * Clients tell each other over [PartyLine.MUTE_KIND] instead.
     */
    val muted: Boolean = false,
)

sealed interface PartyState {
    data object Idle : PartyState
    data class Connecting(val room: String) : PartyState
    data class Live(
        val room: String,
        /** What the line is about, set by an admin. Empty until one
         *  bothers; the UI falls back to the room's own name. */
        val topic: String = "",
        val members: List<PartyMember>,
        val muted: Boolean,
        /** Our own upstream audio: Live once the SFU has our mic. */
        val media: MediaState,
        /**
         * Anonymous listeners, counted by connection.
         *
         * They cannot be folded into [members]: every listen token
         * authenticates as the same subject, so deduplicating by name
         * would report twelve of them as one. Counting connections is
         * the only honest number, and the whole point of showing it is
         * that nobody should be able to forget the line is open.
         */
        val listeners: Int = 0,
        /**
         * Whether our token lets us publish audio. False is a
         * listener: no up link, no mic capture — the bar hides the
         * mic toggle and shows "listening" instead. Wire 5; a
         * pre-roles token reads as a speaker.
         */
        val canSpeak: Boolean = true,
        /** Whether the SFU granted us operator rights (room admin) —
         *  gates the per-member moderation menu. */
        val ops: Boolean = false,
    ) : PartyState
    data class Failed(val room: String, val why: String) : PartyState
}

/**
 * A party line: the client half of Galène's SFU protocol.
 *
 * The ticket comes from the host ship's %trunk (see the trunkline
 * design, D5) — this class never sees a password or a shared secret,
 * only a short-lived token scoped to one room.
 *
 * Protocol shape (galene-protocol.md):
 *   -> handshake            <- handshake
 *   -> join {token}         <- joined {rtcConfiguration}
 *   -> request {'': audio}  <- offer (one per remote stream)
 *   -> offer (our mic)      <- answer
 *   <-> ice (trickled, per stream id)
 *   <- user add/delete      (the roster)
 */
@OptIn(ExperimentalUuidApi::class)
class PartyLine(
    private val http: HttpClient,
    private val links: PeerLinkFactory,
    private val sounds: CallSoundPlayer = CallSoundPlayer.Noop,
    /** Base backoff before republishing a failed up link. A knob only
     *  so tests can run the retry path in real milliseconds. */
    private val upRetryBaseMs: Long = 2_000L,
) {
    private val scope =
        CoroutineScope(SupervisorJob() + ioDispatcher + backgroundExceptionHandler)
    private val _state = MutableStateFlow<PartyState>(PartyState.Idle)
    val state: StateFlow<PartyState> = _state.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }
    // internal so tests can inject a recording socket and observe the
    // frames moderation sends.
    internal var session: io.ktor.websocket.WebSocketSession? = null
    private var pump: Job? = null
    private val sendLock = Mutex()

    // Stream id -> link. "up" is our mic; the rest are the server's.
    private val downLinks = mutableMapOf<String, PeerLink>()
    private var upLink: PeerLink? = null
    // internal so tests can drive handle() with an abort for our own
    // up stream without going through a real join.
    internal var upId: String = ""
    private val roster = mutableMapOf<String, PartyMember>()
    // stream id -> the ship publishing it, from the offer.
    private val streamOwner = mutableMapOf<String, String>()
    // Galène's own ICE servers, handed to us on join. Held as a field
    // because the down links are built in a different callback than
    // the one that receives them: when this was a local in onJoined,
    // every down link was created with an empty list, gathered host
    // candidates only, and never reached the SFU. Both sides could
    // publish — the listen page heard everyone — and nobody in the
    // app heard anything.
    private var sfuIce: List<IceServer> = emptyList()
    // ships heard from within the last poll or two.
    private var speaking: Set<String> = emptySet()
    // Ships that have told us their mic is off.
    private val mutedBy = mutableSetOf<String>()
    // False until our own join settles. Galène replays the existing
    // roster as a run of user-add messages, and chiming once per
    // person already on the line is not an arrival.
    private var joined = false
    private var levelPoll: Job? = null
    @Volatile private var muted = false
    private var upState: MediaState = MediaState.Idle
    private var room = ""
    private var ourId = ""
    // Galène's client id must be unique per *connection*, not per
    // user: reusing the @p means a rejoin (or a stale socket the
    // server hasn't reaped) is refused with "duplicate client id".
    // The name shown to others comes from the token's subject, so this
    // being opaque costs nothing.
    private var connectionId = ""
    // What our token lets us do on this line — seeded from the
    // ticket's JWT at join, corrected by the server's own "joined"
    // echo, and moved by "change" when an admin edits roles mid-line.
    // internal so tests can seed a listener without a real ticket.
    @Volatile internal var canSpeak = true
    @Volatile internal var ops = false

    /** Join the room named by [ticket]. Idempotent while connected. */
    /** Set the line's topic for display. Comes from the host's room,
     *  not from Galène, which knows nothing about it. */
    fun setTopic(value: String) {
        topic = value
        val cur = _state.value
        if (cur is PartyState.Live) _state.value = cur.copy(topic = value)
    }

    fun join(ticket: TrunkTicket, ourShip: String) {
        if (_state.value is PartyState.Failed) _state.value = PartyState.Idle
        if (_state.value !is PartyState.Idle) return
        room = ticket.name
        ourId = ourShip
        // Join muted. Stepping onto a line should never start
        // broadcasting someone's room before they've decided to
        // speak — the mic button is one tap away, an accidental hot
        // mic is not recoverable.
        muted = true
        leaving = false
        // A new line must not inherit the old line's last server
        // notice as its failure reason. The topic is NOT reset here:
        // every caller sets it (possibly to "") right before join, and
        // wiping it after that call erased every topic ever set.
        lastNotice = null
        connectionId = "$ourShip-${Uuid.random()}"
        upRetries = 3
        upId = "up-$connectionId"
        // The token says what we may do before the server does: a
        // listener must not even try to open a mic. The server's
        // "joined" echo corrects this if the two disagree.
        val perms = TrunkWire.jwtPermissions(ticket.token)
        canSpeak = "present" in perms
        ops = "op" in perms
        _state.value = PartyState.Connecting(ticket.name)
        val old = pump
        pump = scope.launch {
            // A quick leave→join must wait out the old pump: its
            // finally-teardown runs on cancellation, and letting it
            // run AFTER this join stood up a new session tore the new
            // session down — socket closed, up link gone, state
            // clobbered to Idle mid-connect.
            old?.let {
                it.cancel()
                it.join()
                _state.value = PartyState.Connecting(ticket.name)
            }
            run(ticket, ourShip)
        }
    }

    /** Show why a host refused us, so the strip explains itself. */
    fun showRefused(room: String, why: String) {
        if (_state.value is PartyState.Live) return
        _state.value = PartyState.Failed(room, why)
    }

    /** Clear a Failed banner the user has read. Failed is sticky by
     *  design (teardown preserves it so the reason survives the
     *  socket's death) — this is the one way it leaves the screen,
     *  and the floating fallback bar has no other control for it. */
    fun dismissFailure() {
        if (_state.value is PartyState.Failed) _state.value = PartyState.Idle
    }

    fun leave() {
        // Chosen, not suffered: the pump's stream-end path checks this
        // to tell "user left" from "server dropped us".
        leaving = true
        val p = pump
        // pump stays set: join()'s await-the-old-pump guard reads it,
        // and nulling it here handed a quick leave→join an old pump to
        // never wait for — whose delayed teardown then killed the new
        // session. Cancelling an already-cancelled job is free.
        // Feedback now — the strip clears on the tap, not after the
        // close handshake.
        if (_state.value !is PartyState.Failed) _state.value = PartyState.Idle
        scope.launch {
            runCatching { send(buildJsonObject { put("type", "join"); put("kind", "leave"); put("group", galeneGroup) }) }
            // The pump owns teardown (its finally); cancelling runs it
            // exactly once. Tearing down here as well raced a quick
            // re-join — the old pump's delayed finally executed
            // against the NEW session's links.
            if (p != null) p.cancel() else teardown()
        }
    }

    fun setMuted(value: Boolean) {
        muted = value
        upLink?.setMuted(value)
        // publishRoster, not a copy(muted = ...). The top-level flag
        // drives the mic button; our own roster row is rebuilt from
        // `muted` inside publishRoster, so copying only the flag left
        // our row showing the previous state until the next user add or
        // delete happened to republish it.
        publishRoster()
        scope.launch { broadcastMuted() }
    }

    /**
     * Tell the room whether our mic is off.
     *
     * Galène cannot infer this: muting disables the local track, so a
     * muted speaker and a silent one look identical on the wire. A
     * broadcast usermessage is the cheapest channel that reaches both
     * Talon and the listen page, and costs nothing when nobody cares.
     */
    private suspend fun broadcastMuted() {
        send(
            buildJsonObject {
                put("type", "usermessage")
                put("source", connectionId)
                put("dest", "")
                put("username", ourId)
                put("kind", MUTE_KIND)
                put("value", muted)
            },
        )
    }

    private var galeneGroup = ""
    private var topic = ""
    // True from leave() until the next join: the one case where the
    // socket ending is not news.
    private var leaving = false
    // The server's last explanatory usermessage (an operator kick says
    // why before the close lands). Shown as the Failed reason instead
    // of a generic "connection lost" — but only when it arrived just
    // before the close, or an informational notice from hours earlier
    // would masquerade as the reason for an unrelated drop.
    private var lastNotice: String? = null
    private var lastNoticeAtMs = 0L

    private suspend fun run(ticket: TrunkTicket, ourShip: String) {
        try {
            // .status carries the group's canonical name + ws endpoint,
            // so the ship only has to hand us the location URL.
            val status = json.parseToJsonElement(
                http.get(ticket.location.trimEnd('/') + "/.status").bodyAsText(),
            ).jsonObject
            galeneGroup = status["name"]?.jsonPrimitive?.content ?: error("no group name")
            val endpoint = status["endpoint"]?.jsonPrimitive?.content ?: error("no ws endpoint")

            val ws = http.webSocketSession(endpoint)
            session = ws
            send(buildJsonObject {
                put("type", "handshake"); put("id", connectionId)
                putJsonArray("version") { add(kotlinx.serialization.json.JsonPrimitive("2")) }
            })
            send(buildJsonObject {
                put("type", "join"); put("kind", "join")
                put("group", galeneGroup); put("token", ticket.token)
            })

            for (frame in ws.incoming) {
                val text = (frame as? Frame.Text)?.readText() ?: continue
                val msg = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                    ?: continue
                handle(msg)
            }
            Log.i(TAG, "party line stream ended")
            // A clean close we didn't ask for — server restart, an
            // operator kick, an idle reap — used to fall through to
            // Idle, which is exactly the state after a voluntary
            // leave: the strip vanished mid-conversation with zero
            // explanation while everyone else stayed on the line.
            if (joined && !leaving) {
                val fresh = lastNotice?.takeIf {
                    nowMs() - lastNoticeAtMs < NOTICE_FRESH_MS
                }
                _state.value = PartyState.Failed(
                    room,
                    fresh ?: "connection to the line was lost",
                )
            }
        } catch (t: kotlinx.coroutines.CancellationException) {
            // Being cancelled is not a failure — it is leave(), a ship
            // switch closing the shared client, or our owner going
            // away. Recording it as Failed put a sticky red bar at the
            // top of chats reading "Party line: The coroutine scope
            // left the composition" — internal machinery text shown as
            // if the line had broken. Rethrow so cancellation completes
            // properly; teardown() in finally resets the state to Idle.
            Log.i(TAG, "party line cancelled: ${t.message}")
            throw t
        } catch (t: Throwable) {
            Log.e(TAG, "party line failed", t)
            _state.value = PartyState.Failed(room, t.message ?: "connection failed")
        } finally {
            teardown()
        }
    }

    internal suspend fun handle(msg: JsonObject) {
        when (msg["type"]?.jsonPrimitive?.content) {
            "ping" -> send(buildJsonObject { put("type", "pong") })

            "joined" -> when (msg["kind"]?.jsonPrimitive?.content) {
                "join" -> onJoined(msg)
                "change" -> onPermissionsChanged(msg)
                "fail" -> _state.value = PartyState.Failed(
                    room,
                    msg["value"]?.jsonPrimitive?.content ?: "join refused",
                )
                else -> {}
            }

            // The server offers us one stream per remote speaker.
            "offer" -> onRemoteOffer(msg)

            // The server killed a stream. For a down link, drop it —
            // a stale entry would hold a dead connection forever. For
            // OUR up link this is the server saying our mic stream is
            // gone, which nothing else reports; republish rather than
            // sit in a line nobody can hear us in.
            "abort" -> {
                val id = msg["id"]?.jsonPrimitive?.content ?: return
                if (id == upId) {
                    // Budgeted like the Failed retry: a server that
                    // aborts every offer must not spin the mic forever.
                    if (joined && canSpeak && upRetries > 0) {
                        upRetries -= 1
                        Log.w(TAG, "server aborted our up stream; republishing ($upRetries left)")
                        publishUp()
                    }
                } else {
                    downLinks.remove(id)?.close()
                    streamOwner.remove(id)
                    publishRoster()
                }
            }

            "answer" -> {
                val id = msg["id"]?.jsonPrimitive?.content ?: return
                val sdp = msg["sdp"]?.jsonPrimitive?.content ?: return
                if (id == upId) upLink?.applyAnswer(sdp)
            }

            "ice" -> {
                val id = msg["id"]?.jsonPrimitive?.content ?: return
                val c = msg["candidate"]?.jsonObject ?: return
                val cand = IceCandidate(
                    candidate = c["candidate"]?.jsonPrimitive?.content ?: return,
                    sdpMid = c["sdpMid"]?.jsonPrimitive?.content,
                    sdpMLineIndex = c["sdpMLineIndex"]?.jsonPrimitive?.int ?: 0,
                )
                (if (id == upId) upLink else downLinks[id])?.addRemoteCandidate(cand)
            }

            "close" -> {
                val id = msg["id"]?.jsonPrimitive?.content ?: return
                downLinks.remove(id)?.close()
            streamOwner.remove(id)
            }

            "user" -> {
                val id = msg["id"]?.jsonPrimitive?.content ?: return
                val name = msg["username"]?.jsonPrimitive?.content ?: id
                when (msg["kind"]?.jsonPrimitive?.content) {
                    "add" -> {
                        // Not for our own arrival, and not for anyone
                        // already here: Galène sends the existing
                        // roster on join, which would otherwise be a
                        // burst of chimes for a room that was quietly
                        // full before we walked in.
                        val firstSeen = !roster.containsKey(id)
                        if (firstSeen && joined && name != ourId) {
                            sounds.play(CallSounds.joined())
                        }
                        roster[id] = PartyMember(id, name)
                        // Someone who just arrived missed every mute
                        // broadcast so far. Say ours again rather than
                        // making them show us wrong until we next touch
                        // the button.
                        if (id != connectionId) scope.launch { broadcastMuted() }
                    }
                    "delete" -> {
                        if (roster[id]?.ship?.let { it != ourId } == true) {
                            sounds.play(CallSounds.left())
                        }
                        // A delete carries no username, so take the ship
                        // from the row we were holding — keying off the
                        // fallback id cleared nothing, and a rejoin came
                        // back still marked muted.
                        val gone = roster.remove(id)?.ship
                        // Only forget them once their last connection is
                        // gone; someone signed in twice is still here.
                        if (gone != null && roster.values.none { it.ship == gone }) {
                            mutedBy.remove(gone)
                        }
                    }
                    else -> {}
                }
                publishRoster()
            }

            "usermessage" -> {
                val kind = msg["kind"]?.jsonPrimitive?.content
                if (kind == MUTE_KIND) {
                    val who = msg["username"]?.jsonPrimitive?.content ?: return
                    // Keyed by ship, not connection: the roster dedupes
                    // by ship too, so a person on two devices reads as
                    // one row and one mute state.
                    val off = msg["value"]?.jsonPrimitive?.content == "true"
                    if (off) mutedBy.add(who) else mutedBy.remove(who)
                    publishRoster()
                    return
                }
                if (kind == "mute") {
                    // Galène's own moderation: an operator asked us to
                    // mute. Every other client honours it; being the
                    // one that keeps broadcasting makes /mute useless
                    // against a Talon participant.
                    Log.i(TAG, "sfu requested mute")
                    setMuted(true)
                    return
                }
                val notice = msg["value"]?.jsonPrimitive?.content
                if (!notice.isNullOrBlank()) {
                    lastNotice = notice
                    lastNoticeAtMs = nowMs()
                }
                Log.w(TAG, "sfu: $notice")
            }
        }
    }

    internal suspend fun onJoined(msg: JsonObject) {
        // Galène hands out its own TURN credentials on join, so party
        // lines need no ICE config from the ship at all.
        val ice = msg["rtcConfiguration"]?.jsonObject
            ?.get("iceServers")?.jsonArray.orEmpty()
            .mapNotNull { el ->
                val o = el.jsonObject
                val url = o["urls"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content
                    ?: o["urls"]?.jsonPrimitive?.content ?: return@mapNotNull null
                IceServer(
                    url = url,
                    user = o["username"]?.jsonPrimitive?.content ?: "",
                    cred = o["credential"]?.jsonPrimitive?.content ?: "",
                )
            }
        sfuIce = ice
        // Anyone reported from here on is genuinely arriving.
        joined = true

        // The server's own word on what we may do beats the JWT's —
        // the host can have edited roles after minting our token.
        permissionsIn(msg)?.let {
            canSpeak = "present" in it
            ops = "op" in it
        }

        // Ask for everyone's audio.
        send(buildJsonObject {
            put("type", "request")
            putJsonObject("request") { putJsonArray("") { add(kotlinx.serialization.json.JsonPrimitive("audio")) } }
        })

        // Publish our mic as one up stream — unless we're a listener,
        // whose whole contract is that no mic is ever captured.
        if (canSpeak) publishUp()
        publishRoster()
    }

    /** The "permissions" array on a joined message, or null when the
     *  server (pre-1.1, or a "change" about something else) omits it. */
    private fun permissionsIn(msg: JsonObject): Set<String>? =
        (msg["permissions"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            ?.toSet()

    /**
     * The server changed our permissions mid-line — an admin edited
     * the speak roles, or moderation-muted us. Present revoked: tear
     * the mic down. Galène 1.1 deletes the up conn server-side on
     * unpresent, but close locally regardless so the state is honest
     * on any server. Present granted: stand the mic back up.
     */
    private suspend fun onPermissionsChanged(msg: JsonObject) {
        val perms = permissionsIn(msg) ?: return
        ops = "op" in perms
        val speak = "present" in perms
        val had = canSpeak
        canSpeak = speak
        when {
            had && !speak -> {
                upWatch?.cancel()
                upWatch = null
                upLink?.close()
                upLink = null
                upState = MediaState.Idle
                // Re-arm the join-muted rule: if speaking is later
                // restored, the mic comes back muted, never live. A
                // restore hours after a revoke must not broadcast
                // whatever room the user is sitting in.
                muted = true
                publishRoster()
            }
            !had && speak -> {
                upRetries = 3
                publishUp()
                publishRoster()
            }
            // Only ops moved (or nothing did); say so.
            else -> publishRoster()
        }
    }

    /** Republish attempts left for the current line. Reset on join and
     *  replenished when a republish actually connects — a budget spent
     *  over hours of recovered blips must not leave the fourth failure,
     *  days later, silently unretried. */
    private var upRetries = 3

    /** The current up link's state collector. Cancelled on republish:
     *  the OLD link's flow keeps emitting after close(), and a stale
     *  collector overwrote upState — the media dot showed Failed while
     *  the new link was live, the exact lie the retry was built to
     *  kill. It also leaked one collector per republish. */
    private var upWatch: Job? = null

    /**
     * Stand up (or re-stand) the mic's up link and offer it.
     *
     * Failure here was the invisible one: the down links and the
     * roster ride separate connections, so a member whose up link
     * died heard everyone, showed as unmuted, and published nothing —
     * with no error anywhere and nothing that ever retried. Now a
     * Failed up link tears down and re-offers, a few times with
     * backoff; if it still won't connect, the roster row's media dot
     * is at least telling the truth.
     */
    private suspend fun publishUp() {
        // The one guard that matters lives here, not at the call
        // sites: a retry coroutine queued just before a revoke
        // landed would otherwise stand up a capturing mic link
        // while the bar says "Listening".
        if (!canSpeak) return
        // Create before close: the platforms refcount the shared audio
        // session by live mic links, and a close-then-create republish
        // transited zero — Android's last-one-out cleanup reset the
        // user's speaker/Bluetooth route and audio mode mid-line.
        val old = upLink
        val up = links.create(sfuIce, sendAudio = true)
        old?.close()
        upLink = up
        startLevelPolling()
        up.setMuted(muted)
        // Say our mute state before anyone renders us as live.
        broadcastMuted()
        upWatch?.cancel()
        upWatch = scope.launch {
            up.state.collect { st ->
                if (upLink !== up) return@collect
                upState = st
                publishRoster()
                if (st == MediaState.Live) upRetries = 3
                if (st == MediaState.Failed && joined && upRetries > 0) {
                    upRetries -= 1
                    val wait = upRetryBaseMs * (3 - upRetries)
                    Log.w(TAG, "up link failed; republishing in ${wait}ms ($upRetries left)")
                    delay(wait)
                    // A fresh coroutine: publishUp cancels this
                    // collector, and a coroutine must not saw off the
                    // branch it is sitting on. canSpeak re-checked:
                    // a revoke during the backoff must win.
                    if (upLink === up && joined && canSpeak) scope.launch { publishUp() }
                }
            }
        }
        up.onLocalCandidate { c -> scope.launch { sendIce(upId, c) } }
        val sdp = up.offer()
        send(buildJsonObject {
            put("type", "offer"); put("id", upId); put("label", "")
            put("username", ourId); put("sdp", sdp)
        })
    }

    internal suspend fun onRemoteOffer(msg: JsonObject) {
        val id = msg["id"]?.jsonPrimitive?.content ?: return
        val sdp = msg["sdp"]?.jsonPrimitive?.content ?: return
        // Galène names the publisher on the offer, which is the only
        // place a stream is tied to a person — the roster is keyed by
        // client, and audio levels arrive per stream.
        msg["username"]?.jsonPrimitive?.content?.let { streamOwner[id] = it }
        // The same servers the up link got. A down link needs them just
        // as much: it is the side that has to traverse to the SFU.
        val link = downLinks.getOrPut(id) {
            links.create(sfuIce, sendAudio = false).also { l ->
                l.onLocalCandidate { c -> scope.launch { sendIce(id, c) } }
            }
        }
        val answer = link.answerTo(sdp)
        send(buildJsonObject { put("type", "answer"); put("id", id); put("sdp", answer) })
    }

    private suspend fun sendIce(id: String, c: IceCandidate) {
        send(buildJsonObject {
            put("type", "ice"); put("id", id)
            putJsonObject("candidate") {
                put("candidate", c.candidate)
                c.sdpMid?.let { put("sdpMid", it) }
                put("sdpMLineIndex", c.sdpMLineIndex)
            }
        })
    }

    private fun publishRoster() {
        if (_state.value is PartyState.Failed) return
        // Members are ships and dedupe by ship: the SFU can still be
        // holding a dead socket from a dropped join, and "~zod, ~zod"
        // helps nobody. Anonymous listeners are not ships — they all
        // authenticate as the same subject — so they are counted by
        // connection instead, or they would collapse into one row.
        val (ships, anon) = roster.values.partition { it.ship.startsWith("~") }
        _state.value = PartyState.Live(
            room = room,
            topic = topic,
            members = ships.distinctBy { it.ship }.sortedBy { it.ship }
                .map {
                    it.copy(
                        speaking = it.ship in speaking,
                        // Our own row reads the local flag: we never
                        // receive our own broadcast, and the button
                        // should agree with the dot beside our name.
                        muted = if (it.ship == ourId) muted else it.ship in mutedBy,
                    )
                },
            muted = muted,
            media = upState,
            listeners = anon.size,
            canSpeak = canSpeak,
            ops = ops,
        )
    }

    /**
     * Op moderation over the socket: revoke (or restore) [ship]'s
     * ability to publish. Sent once per *connection* of that ship — a
     * person on two devices has two, and half-muting them mutes
     * nobody. Galène enforces op rights; a non-op's ask is refused
     * server-side, so there is nothing to gate here. Pair with
     * CallController.moderateMember so the revocation survives a
     * rejoin — this alone only reaches the connections live now.
     */
    fun revokeSpeaking(ship: String) = sendUserAction(ship, "unpresent")

    fun restoreSpeaking(ship: String) = sendUserAction(ship, "present")

    private fun sendUserAction(ship: String, kind: String) {
        // Snapshot before launching: the roster mutates on the pump.
        val targets = roster.values.filter { it.ship == ship }.map { it.id }
        scope.launch {
            for (dest in targets) {
                send(
                    buildJsonObject {
                        put("type", "useraction")
                        put("source", connectionId)
                        put("dest", dest)
                        put("username", ourId)
                        put("kind", kind)
                    },
                )
            }
        }
    }

    /**
     * Poll who is talking.
     *
     * WebRTC reports audio level per stream, and the offer told us
     * which ship each stream belongs to, so this is the join of the
     * two. Polled at a rate a person can perceive rather than per
     * packet — a speaking dot doesn't need 50Hz.
     */
    private fun startLevelPolling() {
        if (levelPoll != null) return
        levelPoll = scope.launch {
            while (true) {
                delay(SPEAKING_POLL_MS)
                val loud = downLinks.entries.mapNotNull { (id, link) ->
                    val ship = streamOwner[id] ?: return@mapNotNull null
                    val level = link.audioLevel() ?: return@mapNotNull null
                    ship.takeIf { level > SPEAKING_THRESHOLD }
                }.toMutableSet()
                // Ourselves, from the up link's own microphone level.
                // Without this there is no way to tell "nobody is
                // talking" from "my microphone is dead", and a muted
                // mic looks exactly like a working one.
                //
                // Skipped while muted: the level still moves — the
                // track is disabled, not the capture — and a dot that
                // lights up while muted says the opposite of the truth.
                if (!muted && ourId.isNotEmpty()) {
                    val own = upLink?.localAudioLevel()
                    if (own != null && own > SPEAKING_THRESHOLD) loud += ourId
                }
                if (loud != speaking) {
                    speaking = loud
                    publishRoster()
                }
            }
        }
    }

    private suspend fun send(obj: JsonObject) {
        val ws = session ?: return
        sendLock.withLock {
            runCatching { ws.send(Frame.Text(obj.toString())) }
                .onFailure { Log.w(TAG, "sfu send failed", it) }
        }
    }

    private suspend fun teardown() {
        levelPoll?.cancel()
        levelPoll = null
        upWatch?.cancel()
        upWatch = null
        streamOwner.clear()
        speaking = emptySet()
        joined = false
        mutedBy.clear()
        // Close the socket first, and cleanly. Tearing down the native
        // peer connections takes long enough that Galène saw the gap
        // and then an abrupt EOF rather than a close handshake, and it
        // keeps a client it never saw leave — so everyone else went on
        // showing the person who just left.
        runCatching {
            session?.close(
                CloseReason(CloseReason.Codes.NORMAL, "left"),
            )
        }
        session = null
        upLink?.close()
        upLink = null
        downLinks.values.forEach { it.close() }
        downLinks.clear()
        roster.clear()
        upState = MediaState.Idle
        if (_state.value !is PartyState.Failed) _state.value = PartyState.Idle
    }

    companion object {
        /** Fast enough to look live, slow enough to cost nothing. */
        private const val SPEAKING_POLL_MS = 250L

        /** How recent a server notice must be to count as the reason
         *  the stream then ended. */
        private const val NOTICE_FRESH_MS = 10_000L

        /** Above this counts as talking. WebRTC's level is linear
         *  0..1; room noise sits well below it. */
        private const val SPEAKING_THRESHOLD = 0.02f

        private const val TAG = "PartyLine"

        /**
         * Application-specific usermessage kind carrying mic state.
         *
         * Galène relays kinds it doesn't recognise untouched, and its
         * own client ignores them, so this is additive: an unmodified
         * Galène client on the same line is unaffected.
         */
        internal const val MUTE_KIND = "talon-mute"
    }
}
