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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
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
    /**
     * Whether an operator has muted this person for everyone
     * (Galène `unpresent`). Distinct from [muted], which is a
     * self-mute: an admin mute is not the person's own choice, it
     * survives a rejoin, and it is what the moderation menu toggles.
     * Learned over [PartyLine.ADMIN_MUTE_KIND] since Galène tells
     * only the muted person about their own permission change.
     */
    val mutedByAdmin: Boolean = false,
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
        /** Whether an operator muted US specifically (not a role
         *  listener). Lets the bar say "Muted by an admin" instead of
         *  the neutral "Listening". */
        val selfMutedByAdmin: Boolean = false,
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
    /** Whether this platform can capture and render party-line video
     *  (isPartyVideoSupported). Off keeps the SFU request audio-only so
     *  a client with no video path (iOS) never gets video m-lines. */
    private val videoSupported: Boolean = false,
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
    // Ships an operator has muted for everyone. Fed by ADMIN_MUTE_KIND
    // broadcasts (Galène only tells the muted person themselves), so
    // every client can mark them and show the right moderation action.
    private val adminMuted = mutableSetOf<String>()
    // False until our own join settles. Galène replays the existing
    // roster as a run of user-add messages, and chiming once per
    // person already on the line is not an arrival.
    private var joined = false
    private var levelPoll: Job? = null
    @Volatile private var muted = false
    private var upState: MediaState = MediaState.Idle

    // Call recording (wire 7). recClips maps a speaker's patp to their
    // capture buffer. Structural writes (adding a speaker) happen on the
    // app thread in +tap; the WebRTC audio thread only touches an
    // already-registered [Clip] — never the map itself — so no lock is
    // needed. Reads at stop() are fenced by Clip.open (see stopRecording).
    /**
     * Whether we are capturing right now. A StateFlow, and owned here,
     * because the screen must not be able to disagree with the tap: the
     * recording controls used to live in the chat slot, so opening a DM
     * cleared the room-wide badge while the taps kept running.
     */
    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()
    private var recStartMs = 0L
    private var recRate = 48_000
    /** The patp the up link is filed under while recording, so a
     *  republish can re-tap our own mic under the same key. */
    private var recSelfShip: String? = null
    private val recClips = mutableMapOf<String, Clip>()

    /**
     * One speaker's capture buffer. The audio thread appends to [chunks]
     * and stamps [rate]/[frames]; it never touches the enclosing map.
     * [open] is the fence stop() closes before it reads.
     */
    private class Clip {
        val chunks = mutableListOf<ByteArray>()
        @Volatile var rate = 0
        /** Real captured frames, as opposed to the silence we padded in.
         *  Zero means this speaker never spoke while we recorded, and the
         *  clip is pure padding — not worth a Whisper upload. */
        @Volatile var frames = 0
        @Volatile var open = true
    }

    /**
     * The finished recording. Published rather than only returned so a
     * recording finalized by [teardown] — leaving the line, or the app
     * closing the screen — still reaches the UI instead of being lost
     * with the control that would have stopped it.
     */
    private val _lastRecording = MutableStateFlow<RecordedCall?>(null)
    val lastRecording: StateFlow<RecordedCall?> = _lastRecording.asStateFlow()

    /** Drop the finished recording once the UI has dealt with it. */
    fun clearLastRecording() { _lastRecording.value = null }

    // Party-line video (conference). Our own camera state; re-applied
    // when the up link republishes so a flaky network doesn't silently
    // drop our video. Rendering reads links directly (localVideoLink /
    // videoLinkFor) and collects their PeerLink.video.
    private val _cameraOn = MutableStateFlow(false)
    val cameraOn: StateFlow<Boolean> = _cameraOn.asStateFlow()
    // The pinned speaker who gets full-resolution video; everyone else
    // stays video-low so a big room's bandwidth and decode stay bounded.
    private val _focusedVideo = MutableStateFlow<String?>(null)
    val focusedVideo: StateFlow<String?> = _focusedVideo.asStateFlow()
    // CONNECTIONS whose camera is ON right now, from explicit VIDEO_KIND
    // broadcasts. A down link always has an empty video transceiver, so
    // track presence can't tell camera-on from off — this signal is the
    // truth the tiles render from.
    //
    // Keyed by connection, not by ship (unlike MUTE_KIND, where one row
    // per person is the point): every client re-broadcasts its camera
    // flag on every roster add, so the same person signed in twice had
    // their second device's "camera off" erase the first device's live
    // camera for the whole room.
    private val videoOnBy = mutableSetOf<String>()
    private val _videoOn = MutableStateFlow<Set<String>>(emptySet())
    val videoOn: StateFlow<Set<String>> = _videoOn.asStateFlow()
    // The up link, as a flow, so a self-preview tile re-binds when the
    // link republishes even if PartyState didn't change.
    private val _upLink = MutableStateFlow<PeerLink?>(null)
    val localVideoLink: StateFlow<PeerLink?> = _upLink.asStateFlow()

    private fun refreshVideoOn() {
        // Connections -> the ships they belong to. A person is
        // camera-on if ANY of their connections is.
        val ships = videoOnBy.mapNotNull { roster[it]?.ship }.toSet()
        _videoOn.value = ships + (if (_cameraOn.value) setOf(ourId) else emptySet())
    }
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

    /**
     * Start capturing every speaker's audio, including our own mic.
     *
     * [ourShip] tags the up link. Down links that join mid-recording
     * are tapped as their offers arrive (see [onRemoteOffer]). Yields
     * audio only where the platform implements [PeerLink.onPcm]; the
     * UI is gated on [io.nisfeb.talon.ui.isCallRecordingSupported].
     */
    fun startRecording(ourShip: String) {
        if (_recording.value) return
        recStartMs = nowMs()
        recClips.clear()
        recSelfShip = ourShip
        _recording.value = true
        Log.i(
            TAG,
            "recording start: upLink=${upLink != null} downLinks=${downLinks.size} " +
                "ourShip=$ourShip canSpeak=$canSpeak",
        )
        tap(upLink, ourShip, isSelf = true)
        for ((id, link) in downLinks) tap(link, streamOwner[id] ?: id)
    }

    /**
     * Register a per-speaker tap, padding the clip with silence up to
     * now so every speaker shares t=0.
     *
     * Re-entrant on purpose: an up-link republish or a ship rejoining on
     * a new stream re-taps a speaker we already hold audio for, so the
     * existing clip is kept and only the gap is padded. Replacing it
     * (what this used to do) silently threw away everything captured
     * before a mid-recording network blip.
     */
    private fun tap(link: PeerLink?, ship: String, isSelf: Boolean = false) {
        link ?: return
        val clip = recClips.getOrPut(ship) { Clip() }
        clip.open = true
        val rate = if (clip.rate > 0) clip.rate else recRate
        val elapsedMs = (nowMs() - recStartMs).coerceAtLeast(0)
        val wantBytes = ((rate.toLong() * elapsedMs / 1000L).toInt()) * 2
        pad(clip.chunks, wantBytes - clip.chunks.sumOf { it.size })
        link.onPcm { pcm, r ->
            // Mute is the app's one hard privacy control, and both
            // platforms tap outside the send path — Android's sink sits
            // pre-encode, desktop's is a separate capture entirely — so
            // without this a muted aside still reaches the WAV, Whisper
            // and the published transcript.
            if (isSelf && muted) return@onPcm
            if (!clip.open) return@onPcm
            clip.rate = r
            clip.frames++
            if (r > recRate) recRate = r
            clip.chunks.add(pcm)
        }
    }

    /** Append [bytes] of silence as bounded chunks. One contiguous array
     *  is a ~170 MB allocation for a speaker joining 30 minutes in, on
     *  the audio pump; these cost the same in total and never spike. */
    private fun pad(chunks: MutableList<ByteArray>, bytes: Int) {
        var left = bytes
        while (left > 0) {
            val n = if (left < PAD_CHUNK_BYTES) left else PAD_CHUNK_BYTES
            chunks.add(ByteArray(n))
            left -= n
        }
    }

    /** Stop capturing and return the per-speaker audio. Removes every
     *  native sink first, so no callback races the read. */
    fun stopRecording(): RecordedCall {
        if (!_recording.value) return RecordedCall(emptyMap(), recRate)
        _recording.value = false
        upLink?.onPcm(null)
        downLinks.values.forEach { runCatching { it.onPcm(null) } }
        // Close every clip before reading. Dropping the sinks is not
        // quite enough on its own: a frame already inside the callback
        // would otherwise append while we concatenate.
        recClips.values.forEach { it.open = false }
        val clips = mutableMapOf<String, ByteArray>()
        val rates = mutableMapOf<String, Int>()
        val entries = recClips.entries.iterator()
        while (entries.hasNext()) {
            val (ship, clip) = entries.next()
            // Free each speaker's chunks as we go. Building the whole
            // result while still holding every chunk doubled peak heap,
            // which is what turned a long Android recording into an OOM
            // on the Stop tap that was meant to save it.
            if (clip.frames > 0) {
                val total = clip.chunks.sumOf { it.size }
                val out = ByteArray(total)
                var at = 0
                for (c in clip.chunks) { c.copyInto(out, at); at += c.size }
                clips[ship] = out
                rates[ship] = if (clip.rate > 0) clip.rate else recRate
            }
            clip.chunks.clear()
            entries.remove()
        }
        val mixRate = rates.values.maxOrNull() ?: recRate
        Log.i(
            TAG,
            "recording stop: mixRate=$mixRate " +
                clips.entries.joinToString(", ") {
                    "${it.key}=${it.value.size}B@${rates[it.key]}"
                }.ifEmpty { "(no clips)" },
        )
        recSelfShip = null
        val call = RecordedCall(clips, mixRate, rates)
        _lastRecording.value = call
        return call
    }

    fun isRecording(): Boolean = _recording.value

    /** Our own mic state. internal: the UI reads it off [state], but a
     *  test with no join has no Live state to read. */
    internal fun isSelfMuted(): Boolean = muted

    /**
     * Turn our camera on or off on the line. Returns false if it
     * couldn't open (no device, permission, or we have no up link —
     * a listener). The up link pre-negotiated the video sender, so
     * this needs no renegotiation.
     */
    suspend fun setCameraEnabled(enabled: Boolean): Boolean {
        val up = upLink ?: return false
        val ok = up.setCameraEnabled(enabled)
        if (ok) {
            _cameraOn.value = enabled
            refreshVideoOn()
            // Tell the room: a disabled track still sends (frozen) frames,
            // so peers can't tell camera-off from a still scene without this.
            broadcastVideo()
        }
        return ok
    }

    /** Broadcast our camera on/off so peers' tiles show video vs avatar.
     *  Mirrors [broadcastMuted] — Galène has no notion of camera state. */
    private suspend fun broadcastVideo() {
        runCatching {
            send(
                buildJsonObject {
                    put("type", "usermessage")
                    put("source", connectionId)
                    put("dest", "")
                    put("username", ourId)
                    put("kind", VIDEO_KIND)
                    put("value", _cameraOn.value)
                },
            )
        }
    }

    /** Flip our camera (front/back), where the platform can. */
    fun switchCamera() {
        upLink?.switchCamera()
    }

    /** The down link carrying [ship]'s camera, for that speaker's tile. */
    fun videoLinkFor(ship: String): PeerLink? {
        val id = streamOwner.entries.firstOrNull { it.value == ship }?.key ?: return null
        return downLinks[id]
    }

    /**
     * Pin one speaker to full-resolution video; everyone else stays
     * video-low. Null unpins (all low). Re-sends the Galène request
     * keyed by the pinned stream — an SFU that keys requests by label
     * rather than id simply keeps everyone low, which is the safe
     * fallback. This is the conference scale control: bounded decode
     * and bandwidth regardless of room size.
     */
    suspend fun setFocusedVideo(ship: String?) {
        _focusedVideo.value = ship
        val focusedId = ship?.let { s ->
            streamOwner.entries.firstOrNull { it.value == s }?.key
        }
        runCatching {
            send(buildJsonObject {
                put("type", "request")
                putJsonObject("request") {
                    putJsonArray("") {
                        add(kotlinx.serialization.json.JsonPrimitive("audio"))
                        add(kotlinx.serialization.json.JsonPrimitive("video-low"))
                    }
                    if (focusedId != null) {
                        putJsonArray(focusedId) {
                            add(kotlinx.serialization.json.JsonPrimitive("audio"))
                            add(kotlinx.serialization.json.JsonPrimitive("video"))
                        }
                    }
                }
            })
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
                // One bad frame must not end the line. handle() reaches
                // a lot of parsing, and an unexpected shape (a Galène
                // clearchat carries an object where a string is assumed)
                // used to propagate out of the pump and land the session
                // in Failed with a serialization class name as the reason.
                runCatching { handle(msg) }
                    .onFailure { Log.w(TAG, "ignoring an unhandled sfu frame", it) }
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
            // Galène asks for an ICE restart on a broken up conn. With
            // no case here it was dropped, so recovery waited on
            // libwebrtc's own ~30s failure timeout instead.
            "renegotiate" -> {
                val id = msg["id"]?.jsonPrimitive?.content
                if (id == null || id == upId) scope.launch { publishUp() }
            }
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
                        // A newcomer also missed our camera state; say it
                        // again so our tile shows video, not a stale avatar.
                        if (id != connectionId) scope.launch { broadcastVideo() }
                        // Galène doesn't replay past broadcasts to a
                        // newcomer, so an op re-announces who is
                        // admin-muted — otherwise the mark is invisible
                        // to anyone who joined after the mute.
                        if (id != connectionId && ops && adminMuted.isNotEmpty()) {
                            val snapshot = adminMuted.toList()
                            scope.launch { snapshot.forEach { broadcastAdminMute(it, true) } }
                        }
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
                        // The camera flag is per connection, so this one
                        // goes even if the person is still here on
                        // another device.
                        if (videoOnBy.remove(id)) refreshVideoOn()
                        // Only forget them once their last connection is
                        // gone; someone signed in twice is still here.
                        if (gone != null && roster.values.none { it.ship == gone }) {
                            mutedBy.remove(gone)
                            if (_focusedVideo.value == gone) {
                                scope.launch { setFocusedVideo(null) }
                            }
                        }
                    }
                    else -> {}
                }
                publishRoster()
            }

            "usermessage" -> {
                val kind = msg["kind"]?.jsonPrimitive?.content
                if (kind == ADMIN_MUTE_KIND) {
                    // `target` is where the subject belongs; fall back to
                    // `username` so a peer on an older build still marks
                    // the right person.
                    val who = msg["target"]?.jsonPrimitive?.content
                        ?: msg["username"]?.jsonPrimitive?.content ?: return
                    val on = msg["value"]?.jsonPrimitive?.content == "true"
                    if (on) adminMuted.add(who) else adminMuted.remove(who)
                    publishRoster()
                    return
                }
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
                if (kind == VIDEO_KIND) {
                    // `source` is the connection; username would collapse
                    // a person's two devices into one flag.
                    val who = msg["source"]?.jsonPrimitive?.content
                        ?: msg["username"]?.jsonPrimitive?.content ?: return
                    val on = msg["value"]?.jsonPrimitive?.content == "true"
                    if (on) videoOnBy.add(who) else videoOnBy.remove(who)
                    refreshVideoOn()
                    return
                }
                if (kind == "mute") {
                    // Galène's own moderation: an operator asked us to
                    // mute. Every other client honours it; being the
                    // one that keeps broadcasting makes /mute useless
                    // against a Talon participant.
                    //
                    // `privileged` is stamped by the server from the
                    // sender's op permission. Without checking it, ANY
                    // member could mute the whole room on a loop —
                    // trunk grants every speaker the `message` right
                    // that lets them send this.
                    val privileged =
                        msg["privileged"]?.jsonPrimitive?.content == "true"
                    if (!privileged) {
                        Log.w(TAG, "ignoring a mute request from a non-operator")
                        return
                    }
                    Log.i(TAG, "sfu requested mute")
                    setMuted(true)
                    return
                }
                // Not every usermessage carries a string here: a
                // clearchat sends null and a filetransfer an object, and
                // .jsonPrimitive throws on both.
                val notice = (msg["value"] as? JsonPrimitive)
                    ?.takeUnless { it is JsonNull }?.content
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

        // Ask for everyone's audio, and camera where we can render it.
        // video-low keeps non-focused tiles cheap (see setFocusedVideo);
        // a platform with no video path (iOS) requests audio only so the
        // SFU never offers it video m-lines it can't answer.
        send(buildJsonObject {
            put("type", "request")
            putJsonObject("request") {
                putJsonArray("") {
                    add(kotlinx.serialization.json.JsonPrimitive("audio"))
                    if (videoSupported) {
                        add(kotlinx.serialization.json.JsonPrimitive("video-low"))
                    }
                }
            }
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
    /** Whether we have offered an up stream yet this join, so the first
     *  publish doesn't claim to replace an id the server never saw. */
    private var upOffered = false

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
        // A fresh stream id per publish, naming the one it replaces.
        // Re-offering a brand-new PeerConnection under the SAME id makes
        // Galène hand the offer to the existing server-side conn, which
        // keeps the dead connection's DTLS fingerprint — media never
        // comes back, so every recovery left the mic dead while down
        // links kept working: "I hear everyone, nobody hears me".
        val previousUpId = if (upOffered) upId else ""
        upId = "up-$connectionId-${Uuid.random()}"
        val old = upLink
        val up = links.create(sfuIce, sendAudio = true)
        old?.close()
        upLink = up
        _upLink.value = up
        startLevelPolling()
        up.setMuted(muted)
        // Carry an in-flight recording across the republish. The old
        // link's tap died with it, so without this our own voice simply
        // stops partway through the file while everyone else continues.
        recSelfShip?.let { if (_recording.value) tap(up, it, isSelf = true) }
        // Republish (flaky network) rebuilds the up link with the camera
        // closed; restore it so our video doesn't silently vanish.
        if (_cameraOn.value) scope.launch { up.setCameraEnabled(true) }
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
        upOffered = true
        send(buildJsonObject {
            put("type", "offer"); put("id", upId); put("label", "")
            put("username", ourId); put("sdp", sdp)
            // Tells Galène to delUpConn the corpse rather than keep it.
            if (previousUpId.isNotEmpty()) put("replace", previousUpId)
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
        val fresh = id !in downLinks
        val link = downLinks.getOrPut(id) {
            links.create(sfuIce, sendAudio = false).also { l ->
                l.onLocalCandidate { c -> scope.launch { sendIce(id, c) } }
            }
        }
        // A speaker who joins while we're recording gets tapped too.
        if (_recording.value && fresh) tap(link, streamOwner[id] ?: id)
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
                        mutedByAdmin = it.ship in adminMuted,
                    )
                },
            muted = muted,
            media = upState,
            listeners = anon.size,
            canSpeak = canSpeak,
            ops = ops,
            selfMutedByAdmin = ourId in adminMuted,
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
    fun revokeSpeaking(ship: String) {
        adminMuted.add(ship)
        publishRoster()
        scope.launch { broadcastAdminMute(ship, true) }
        sendUserAction(ship, "unpresent")
    }

    fun restoreSpeaking(ship: String) {
        adminMuted.remove(ship)
        publishRoster()
        scope.launch { broadcastAdminMute(ship, false) }
        sendUserAction(ship, "present")
    }

    /** Tell the whole group an operator muted (or unmuted) [ship].
     *  Galène tells only the affected person about their own
     *  permission change, so this broadcast is how the mark reaches
     *  everyone else — and the muted person, so they can be told it
     *  was an admin, not their own choice. */
    private suspend fun broadcastAdminMute(ship: String, muted: Boolean) {
        send(
            buildJsonObject {
                put("type", "usermessage")
                put("source", connectionId)
                put("dest", "")
                // `username` is who is SENDING, everywhere else in this
                // protocol and to the SFU. This one broadcast put the
                // target there instead, which is a claim to be someone
                // else — a server that validates it drops the operator's
                // socket, and one that rewrites it silently retargets the
                // mute at the operator. The target rides its own field.
                put("username", ourId)
                put("target", ship)
                put("kind", ADMIN_MUTE_KIND)
                put("value", muted)
            },
        )
    }

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
        _upLink.value = null
        downLinks.values.forEach { it.close() }
        downLinks.clear()
        roster.clear()
        adminMuted.clear()
        // Video state is per-session: a fresh line starts camera-off,
        // nobody pinned, no remembered camera flags — otherwise a rejoin
        // would auto-open the camera (via publishUp) with no user intent.
        // Finalize a running recording rather than dropping it. Leaving
        // used to strand the capture — the Stop control goes with the
        // line, so the only caller of stopRecording became unreachable —
        // and left `recording` stuck true, which made every later
        // startRecording a silent no-op. stopRecording publishes to
        // lastRecording, so the audio still reaches the UI.
        if (_recording.value) {
            runCatching { stopRecording() }
                .onFailure { Log.w(TAG, "could not finalize the recording on teardown", it) }
        }
        recClips.clear()
        recSelfShip = null
        recStartMs = 0L
        upOffered = false
        _cameraOn.value = false
        _focusedVideo.value = null
        videoOnBy.clear()
        _videoOn.value = emptySet()
        upState = MediaState.Idle
        if (_state.value !is PartyState.Failed) _state.value = PartyState.Idle
    }

    companion object {
        /** Fast enough to look live, slow enough to cost nothing. */
        private const val SPEAKING_POLL_MS = 250L

        /** Silence padding is emitted in chunks this size (64 KB ~= 0.7 s
         *  of 16-bit 48 kHz mono) so a late joiner never asks for one
         *  enormous contiguous allocation on the audio pump. */
        private const val PAD_CHUNK_BYTES = 64 * 1024

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
        internal const val ADMIN_MUTE_KIND = "talon-adminmute"
        internal const val VIDEO_KIND = "talon-video"
    }
}
