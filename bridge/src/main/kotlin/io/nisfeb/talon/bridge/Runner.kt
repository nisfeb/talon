package io.nisfeb.talon.bridge

import io.nisfeb.talon.call.CallController
import io.nisfeb.talon.call.DesktopCallEngineProvider
import io.nisfeb.talon.call.DesktopPeerLink
import io.nisfeb.talon.call.DesktopWebRtcFactory
import io.nisfeb.talon.call.PartyLine
import io.nisfeb.talon.call.PartyMember
import io.nisfeb.talon.call.PartyState
import io.nisfeb.talon.call.PeerLinkFactory
import io.nisfeb.talon.urbit.SavedSession
import io.nisfeb.talon.urbit.SessionStore
import io.nisfeb.talon.urbit.UrbitSession
import io.nisfeb.talon.util.Log
import io.nisfeb.talon.util.createAppHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One bridge session: log in, ask the host for the line, sit on it.
 * The CLI drives one of these to completion; the Party Manager window
 * starts and stops them at will and watches [status].
 */
class BridgeRunner {
    sealed interface Status {
        data object Idle : Status
        data class Busy(val what: String) : Status
        /** Logged in with the lines loaded; [error] is the last failed join, if any. */
        data class Connected(val ship: String, val error: String? = null) : Status
        data class Live(
            val ship: String,
            val host: String,
            val room: String,
            val title: String,
            val members: List<PartyMember>,
            val muted: Boolean,
        ) : Status
        data class Failed(val why: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** A party line we could ask for: hosted by us or invited to. */
    data class LineInfo(val host: String, val name: String, val title: String) {
        val key get() = "$host/$name"
        val label get() = title.ifBlank { key }
    }

    private val _lines = MutableStateFlow<List<LineInfo>>(emptyList())
    val lines: StateFlow<List<LineInfo>> = _lines.asStateFlow()

    private var line: PartyLine? = null
    private var controller: CallController? = null
    private var audio: BridgeAudio? = null
    private var job: Job? = null

    private var scope: CoroutineScope? = null
    private var ship: String = ""
    private var deviceMode = false
    private var playsFile = false

    /** Logs in and loads the lines; [status] becomes [Status.Connected]. */
    fun connect(config: Config, scope: CoroutineScope) {
        if (job?.isActive == true) return
        this.scope = scope
        job = scope.launch {
            try {
                doConnect(config)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e.message ?: e.toString())
                teardown()
            }
        }
    }

    /** Asks [host] for [room]; needs [Status.Connected]. */
    fun join(host: String, room: String) {
        val scope = scope ?: return
        if (_status.value !is Status.Connected) return
        job = scope.launch {
            try {
                doJoin(host, room)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.value = Status.Connected(ship, e.message ?: e.toString())
            }
        }
    }

    /** Leaves the line but stays logged in. */
    fun leave() {
        job?.cancel()
        job = null
        runCatching { line?.leave() }
        if (ship.isNotEmpty()) _status.value = Status.Connected(ship)
    }

    fun stop() {
        job?.cancel()
        job = null
        linesJob?.cancel()
        linesJob = null
        teardown()
        ship = ""
        _lines.value = emptyList()
        _status.value = Status.Idle
    }

    fun setMuted(muted: Boolean) {
        line?.setMuted(muted)
    }

    private var linesJob: Job? = null

    private suspend fun doConnect(config: Config) {
        _status.value = Status.Busy("starting")
        deviceMode = config.audioIn != null || config.audioOut != null
        playsFile = config.play != null
        val audio = if (deviceMode) {
            null
        } else {
            BridgeAudio(
                source = when {
                    config.play != null -> WavPcmSource(config.play, config.loop)
                    else -> PcmSource.Silent
                },
                sink = config.record?.let(::WavPcmSink) ?: PcmSink.Discard,
            ).also { it.start() }
        }
        this.audio = audio
        val peerLinks = if (deviceMode) devicePeerLinks(config) else audio!!.peerLinks

        val http = createAppHttpClient()
        val session = UrbitSession(http, MemoryStore())
        val controller = CallController(session, DesktopCallEngineProvider).also { this.controller = it }
        val line = PartyLine(http, peerLinks).also { this.line = it }

        _status.value = Status.Busy("logging into ${config.shipUrl}")
        val ship = session.login(config.shipUrl, config.shipCode).getOrElse {
            fail("could not log into ${config.shipUrl}: ${it.message}")
            teardown()
            return
        }
        this.ship = ship
        Log.i(TAG, "logged in as $ship")

        controller.onTicket = { host, ticket ->
            Log.i(TAG, "granted a line on $host/${ticket.name}")
            line.setTopic(controller.lineFor(host, ticket.name)?.title.orEmpty())
            line.join(ticket, ship)
        }
        controller.onDenied = { name, why -> _status.value = Status.Connected(ship, "$name refused us a line: $why") }
        controller.start()

        linesJob = scope?.launch {
            combine(controller.rooms, controller.invites) { rooms, invites ->
                (rooms.map { (key, r) -> LineInfo(key.substringBefore('/'), r.name, r.title) } +
                    invites.values.map { LineInfo(it.host, it.name, it.title) })
                    .distinctBy { it.key }
                    .sortedBy { it.label.lowercase() }
            }.collect { _lines.value = it }
        }

        // Ask only once the calls subscription is live: as our own host
        // the grant comes back within a millisecond, and a fact with no
        // subscriber yet is simply dropped.
        withTimeoutOrNull(30_000) {
            controller.connected.first { it }
        } ?: Log.w(TAG, "the calls subscription never came up")
        _status.value = Status.Connected(ship)
    }

    private suspend fun doJoin(host: String, room: String) {
        val controller = controller ?: return
        val line = line ?: return
        _status.value = Status.Busy("asking $host for $room")
        Log.i(TAG, "asking $host for $room")
        controller.joinRoom(host, room)

        val live = withTimeoutOrNull(JOIN_TIMEOUT_MS) {
            while (line.state.value !is PartyState.Live) {
                (line.state.value as? PartyState.Failed)?.let {
                    _status.value = Status.Connected(ship, "could not join: ${it.why}")
                    return@withTimeoutOrNull false
                }
                if (_status.value !is Status.Busy) return@withTimeoutOrNull false
                delay(200)
            }
            true
        }
        if (live == null) {
            _status.value = Status.Connected(ship, "no answer from $host within ${JOIN_TIMEOUT_MS / 1000}s — is our ship in the group?")
        }
        if (live != true) return
        if (deviceMode || playsFile) line.setMuted(false)
        Log.i(TAG, "on the line")

        line.state.collect { s ->
            _status.value = when (s) {
                is PartyState.Live -> Status.Live(
                    ship, host, room,
                    controller.lineFor(host, room)?.title.orEmpty(),
                    s.members, s.muted,
                )
                is PartyState.Failed -> Status.Connected(ship, s.why)
                else -> Status.Busy("reconnecting")
            }
        }
    }

    private fun devicePeerLinks(config: Config): PeerLinkFactory {
        // We relay a clean virtual device, often music: no speech
        // processing (it eats music and cancels what plays out the
        // other way), and a music bitrate instead of the 32 kbps default.
        DesktopWebRtcFactory.audioProcessing = false
        DesktopWebRtcFactory.opusMaxAverageBitrate = MUSIC_BITRATE_BPS
        // The factory is built once per process; a second run keeps the first ADM.
        runCatching {
            DesktopWebRtcFactory.useAudioDeviceModule {
                dev.onvoid.webrtc.media.audio.AudioDeviceModule()
            }
        }
        var picked = false
        return PeerLinkFactory { ice, send ->
            if (!picked) {
                picked = true
                val adm = DesktopWebRtcFactory.audioDeviceModule()
                fun pick(want: String?, devices: List<dev.onvoid.webrtc.media.audio.AudioDevice>) =
                    want?.takeIf { it != DEFAULT_DEVICE }?.let { w ->
                        devices.firstOrNull { it.name.contains(w, ignoreCase = true) }
                    }
                pick(config.audioOut, adm.playoutDevices)
                    ?.let { adm.setPlayoutDevice(it); Log.i(TAG, "playout: ${it.name}") }
                    ?: Log.i(TAG, "playout: system default")
                pick(config.audioIn, adm.recordingDevices)
                    ?.let { adm.setRecordingDevice(it); Log.i(TAG, "capture: ${it.name}") }
                    ?: Log.i(TAG, "capture: system default")
            }
            DesktopPeerLink(ice, send)
        }
    }

    private fun fail(why: String) {
        Log.w(TAG, why)
        _status.value = Status.Failed(why)
    }

    private fun teardown() {
        runCatching { line?.leave() }
        runCatching { controller?.stop() }
        runCatching { audio?.close() }
        line = null
        controller = null
        audio = null
    }

    private companion object {
        const val JOIN_TIMEOUT_MS = 60_000L
        const val MUSIC_BITRATE_BPS = 128_000
        const val TAG = "Bridge"
    }
}

private class MemoryStore : SessionStore {
    private var entry: SavedSession? = null
    override fun all() = listOfNotNull(entry)
    override fun active() = entry
    override fun activeShip() = entry?.ship
    override fun save(entry: SavedSession, makeActive: Boolean) { this.entry = entry }
    override fun setActive(ship: String) {}
    override fun remove(ship: String) { entry = null }
    override fun clearAll() { entry = null }
}
