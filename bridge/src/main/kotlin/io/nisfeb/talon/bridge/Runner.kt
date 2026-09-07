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

    private var line: PartyLine? = null
    private var controller: CallController? = null
    private var audio: BridgeAudio? = null
    private var job: Job? = null

    fun start(config: Config, scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                run(config)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e.message ?: e.toString())
                teardown()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        teardown()
        _status.value = Status.Idle
    }

    fun setMuted(muted: Boolean) {
        line?.setMuted(muted)
    }

    private suspend fun run(config: Config) {
        _status.value = Status.Busy("starting")
        val deviceMode = config.audioIn != null || config.audioOut != null
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
        Log.i(TAG, "logged in as $ship")

        controller.onTicket = { host, ticket ->
            Log.i(TAG, "granted a line on $host/${ticket.name}")
            line.setTopic(controller.lineFor(host, ticket.name)?.title.orEmpty())
            line.join(ticket, ship)
        }
        controller.onDenied = { name, why -> fail("$name refused us a line: $why") }
        controller.start()

        // Ask only once the calls subscription is live: as our own host
        // the grant comes back within a millisecond, and a fact with no
        // subscriber yet is simply dropped.
        withTimeoutOrNull(30_000) {
            controller.connected.first { it }
        } ?: Log.w(TAG, "the calls subscription never came up; asking anyway")

        _status.value = Status.Busy("asking ${config.host} for ${config.room}")
        Log.i(TAG, "asking ${config.host} for ${config.room}")
        controller.joinRoom(config.host, config.room)

        val live = withTimeoutOrNull(JOIN_TIMEOUT_MS) {
            while (line.state.value !is PartyState.Live) {
                (line.state.value as? PartyState.Failed)?.let {
                    fail("could not join: ${it.why}")
                    return@withTimeoutOrNull false
                }
                if (_status.value is Status.Failed) return@withTimeoutOrNull false
                delay(200)
            }
            true
        }
        if (live == null) {
            fail("no answer from ${config.host} within ${JOIN_TIMEOUT_MS / 1000}s — is our ship in the group?")
        }
        if (live != true) {
            teardown()
            return
        }
        if (deviceMode || config.play != null) line.setMuted(false)
        Log.i(TAG, "on the line")

        line.state.collect { s ->
            _status.value = when (s) {
                is PartyState.Live -> Status.Live(
                    ship, config.host, config.room,
                    controller.lineFor(config.host, config.room)?.title.orEmpty(),
                    s.members, s.muted,
                )
                is PartyState.Failed -> Status.Failed(s.why)
                else -> Status.Busy("reconnecting")
            }
        }
    }

    private fun devicePeerLinks(config: Config): PeerLinkFactory {
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
