package io.nisfeb.talon.bridge

import io.nisfeb.talon.call.CallController
import io.nisfeb.talon.call.DesktopCallEngineProvider
import io.nisfeb.talon.call.PartyLine
import io.nisfeb.talon.call.PartyState
import io.nisfeb.talon.urbit.SavedSession
import io.nisfeb.talon.urbit.SessionStore
import io.nisfeb.talon.urbit.UrbitSession
import io.nisfeb.talon.util.Log
import io.nisfeb.talon.util.createAppHttpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.system.exitProcess

/**
 * A headless party-line participant.
 *
 * Start it and it logs into its ship, asks a host for a line, joins,
 * and moves audio between that line and a [PcmSource] / [PcmSink]
 * pair. Nothing to click, no window, no sound card.
 *
 * It has its own ship on purpose. Tickets are short-lived and minted
 * per member, so a long-running bridge has to ask for them the way
 * any client does — which means putting its ship in the group and
 * letting the host's membership check work unmodified. It appears in
 * the roster as itself, and it can be kicked. Nothing in %trunk
 * special-cases it, and the trust model is not weakened for the
 * convenience: anyone on the line can see the bridge is there, the
 * same way they can see a listen link.
 */
object Bridge {

    @JvmStatic
    fun main(args: Array<String>) {
        val configFile = args.firstOrNull()?.let(::File) ?: File("bridge.properties")
        val config = runCatching { Config.load(configFile) }.getOrElse {
            System.err.println("talon-bridge: ${it.message}")
            exitProcess(2)
        }
        Log.i(TAG, "starting: $config")

        val audio = BridgeAudio(
            source = config.play?.let { WavPcmSource(it, config.loop) } ?: PcmSource.Silent,
            sink = config.record?.let(::WavPcmSink) ?: PcmSink.Discard,
        )
        // Before anything else touches WebRTC: the factory captures
        // the audio device it was built with, and a later one has no
        // effect on tracks already sourced from the first.
        audio.start()

        val http = createAppHttpClient()
        val session = UrbitSession(http, MemoryStore())
        val controller = CallController(
            session,
            DesktopCallEngineProvider,
        )
        val line = PartyLine(http, audio.peerLinks)

        // The recording is only correct once its header has been
        // patched with the final length, so Ctrl-C has to run close.
        Runtime.getRuntime().addShutdownHook(
            Thread {
                Log.i(TAG, "leaving the line")
                runCatching { line.leave() }
                runCatching { controller.stop() }
                runCatching { audio.close() }
            },
        )

        runBlocking {
            val ship = session.login(config.shipUrl, config.shipCode).getOrElse {
                System.err.println("talon-bridge: could not log into ${config.shipUrl}: ${it.message}")
                exitProcess(1)
            }
            Log.i(TAG, "logged in as $ship")

            controller.onTicket = { host, ticket ->
                Log.i(TAG, "granted a line on $host/${ticket.name}")
                line.setTopic(controller.lineFor(host, ticket.name)?.title.orEmpty())
                line.join(ticket, ship)
            }
            controller.onDenied = { name, why ->
                System.err.println("talon-bridge: $name refused us a line: $why")
                exitProcess(1)
            }
            controller.start()

            // The controller needs its channel and the ship's config
            // before a join can go anywhere.
            withTimeoutOrNull(30_000) {
                while (controller.wire.value == 0) delay(200)
            } ?: Log.w(TAG, "the ship never reported a wire version; asking anyway")

            Log.i(TAG, "asking ${config.host} for ${config.room}")
            controller.joinRoom(config.host, config.room)

            val live = withTimeoutOrNull(JOIN_TIMEOUT_MS) {
                while (line.state.value !is PartyState.Live) {
                    (line.state.value as? PartyState.Failed)?.let {
                        System.err.println("talon-bridge: could not join: ${it.why}")
                        exitProcess(1)
                    }
                    delay(200)
                }
                true
            }
            if (live == null) {
                System.err.println(
                    "talon-bridge: no answer from ${config.host} within " +
                        "${JOIN_TIMEOUT_MS / 1000}s — is our ship in the group?",
                )
                exitProcess(1)
            }
            Log.i(TAG, "on the line")

            // Nothing left to drive: the audio pump has its own
            // thread and the line has its own coroutines. Report what
            // the line looks like now and then so a daemon's log
            // shows it is alive.
            while (true) {
                delay(60_000)
                val state = line.state.value
                if (state is PartyState.Live) {
                    Log.i(TAG, "on the line with ${state.members.size}")
                } else {
                    Log.w(TAG, "no longer live: $state")
                }
            }
        }
    }

    /** How long to wait for the host to mint us a ticket and join. */
    private const val JOIN_TIMEOUT_MS = 60_000L
    private const val TAG = "Bridge"
}

/**
 * The bridge logs in fresh every start, so there is nothing to
 * persist — and a daemon writing a cookie to disk is a credential
 * on disk nobody asked for.
 */
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
