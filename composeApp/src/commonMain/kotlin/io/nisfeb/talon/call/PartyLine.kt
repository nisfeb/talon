package io.nisfeb.talon.call

import io.ktor.client.HttpClient
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
) {
    private val scope =
        CoroutineScope(SupervisorJob() + ioDispatcher + backgroundExceptionHandler)
    private val _state = MutableStateFlow<PartyState>(PartyState.Idle)
    val state: StateFlow<PartyState> = _state.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }
    private var session: io.ktor.websocket.WebSocketSession? = null
    private var pump: Job? = null
    private val sendLock = Mutex()

    // Stream id -> link. "up" is our mic; the rest are the server's.
    private val downLinks = mutableMapOf<String, PeerLink>()
    private var upLink: PeerLink? = null
    private var upId: String = ""
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
    private var muted = false
    private var upState: MediaState = MediaState.Idle
    private var room = ""
    private var ourId = ""
    // Galène's client id must be unique per *connection*, not per
    // user: reusing the @p means a rejoin (or a stale socket the
    // server hasn't reaped) is refused with "duplicate client id".
    // The name shown to others comes from the token's subject, so this
    // being opaque costs nothing.
    private var connectionId = ""

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
        connectionId = "$ourShip-${Uuid.random()}"
        upId = "up-$connectionId"
        _state.value = PartyState.Connecting(ticket.name)
        pump = scope.launch { run(ticket, ourShip) }
    }

    /** Show why a host refused us, so the strip explains itself. */
    fun showRefused(room: String, why: String) {
        if (_state.value is PartyState.Live) return
        _state.value = PartyState.Failed(room, why)
    }

    fun leave() {
        scope.launch {
            runCatching { send(buildJsonObject { put("type", "join"); put("kind", "leave"); put("group", galeneGroup) }) }
            teardown()
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
                "fail" -> _state.value = PartyState.Failed(
                    room,
                    msg["value"]?.jsonPrimitive?.content ?: "join refused",
                )
                else -> {}
            }

            // The server offers us one stream per remote speaker.
            "offer" -> onRemoteOffer(msg)

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
                if (msg["kind"]?.jsonPrimitive?.content == MUTE_KIND) {
                    val who = msg["username"]?.jsonPrimitive?.content ?: return
                    // Keyed by ship, not connection: the roster dedupes
                    // by ship too, so a person on two devices reads as
                    // one row and one mute state.
                    val off = msg["value"]?.jsonPrimitive?.content == "true"
                    if (off) mutedBy.add(who) else mutedBy.remove(who)
                    publishRoster()
                    return
                }
                Log.w(TAG, "sfu: ${msg["value"]?.jsonPrimitive?.content}")
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

        // Ask for everyone's audio.
        send(buildJsonObject {
            put("type", "request")
            putJsonObject("request") { putJsonArray("") { add(kotlinx.serialization.json.JsonPrimitive("audio")) } }
        })

        // Publish our mic as one up stream.
        val up = links.create(ice, sendAudio = true)
        upLink = up
        startLevelPolling()
        up.setMuted(muted)
        // We join muted, so say so before anyone renders us as live.
        broadcastMuted()
        scope.launch { up.state.collect { upState = it; publishRoster() } }
        up.onLocalCandidate { c -> scope.launch { sendIce(upId, c) } }
        val sdp = up.offer()
        send(buildJsonObject {
            put("type", "offer"); put("id", upId); put("label", "")
            put("username", ourId); put("sdp", sdp)
        })
        publishRoster()
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
        )
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
                }.toSet()
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
