package io.nisfeb.talon.call

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Galène refuses any message whose `username` is not the one its token
 * gave the socket ("spoofed username") and closes it. Trunk wire 10 mints
 * a comet's token for its mnemonym, so a comet that wrote its @p on its
 * offers was thrown off the line on every try, and the line looped.
 */
class PartyLineCometTest {
    private class Link : PeerLink {
        override val state: StateFlow<MediaState> = MutableStateFlow(MediaState.Idle)
        override fun onLocalCandidate(callback: (IceCandidate) -> Unit) = Unit
        override suspend fun offer(): String = "v=0"
        override suspend fun answerTo(remoteSdp: String): String = "v=0"
        override suspend fun applyAnswer(remoteSdp: String) = Unit
        override fun addRemoteCandidate(candidate: IceCandidate) = Unit
        override fun setMuted(muted: Boolean) = Unit
        override fun close() = Unit
    }

    /** Records what the line says to the SFU. */
    private class RecordingWs : WebSocketSession {
        val sent: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
        override val coroutineContext: CoroutineContext = Job()
        override var masking = false
        override var maxFrameSize = Long.MAX_VALUE
        override val incoming: ReceiveChannel<Frame> = Channel()
        override val outgoing: SendChannel<Frame> = Channel(Channel.UNLIMITED)
        override val extensions: List<WebSocketExtension<*>> = emptyList()
        override suspend fun send(frame: Frame) { (frame as? Frame.Text)?.let { sent += it.readText() } }
        override suspend fun flush() = Unit
        @Deprecated("unused in tests")
        override fun terminate() = Unit
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun token(sub: String) = "e30." +
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"sub":"$sub","permissions":["present","message"]}""".toByteArray()) + ".sig"

    private fun frames(ws: RecordingWs) = ws.sent.map { json.parseToJsonElement(it).jsonObject }

    /** On a line as [ship], whose token names it [username]; the SFU never answers, the test plays it. */
    private fun onLine(ship: String, username: String, body: suspend (PartyLine, RecordingWs) -> Unit) = runBlocking {
        val line = PartyLine(HttpClient(MockEngine { awaitCancellation() }), links = { _, _ -> Link() })
        val ws = RecordingWs()
        try {
            line.join(TrunkTicket("room", "http://sfu.test/x/", token(username)), ship)
            line.session = ws
            body(line, ws)
        } finally {
            line.leave()
        }
    }

    private suspend fun offer(ws: RecordingWs): JsonObject = withTimeout(5_000) {
        while (frames(ws).none { it["type"]?.jsonPrimitive?.content == "offer" }) delay(10)
        frames(ws).first { it["type"]?.jsonPrimitive?.content == "offer" }
    }

    @Test
    fun aCometSpeaksAsItsMnemonymAndIsStillItself() = onLine(COMET, NYM) { line, ws ->
        // Before the server answers, the token's subject is our name.
        line.setMuted(true)
        withTimeout(5_000) { while (frames(ws).none { it["kind"]?.jsonPrimitive?.content == PartyLine.MUTE_KIND }) delay(10) }

        line.handle(json.decodeFromString("""{"type":"joined","kind":"join","username":"$NYM","rtcConfiguration":{"iceServers":[]}}"""))
        assertEquals(NYM, offer(ws)["username"]?.jsonPrimitive?.content, "the offer says what the token says")
        val names = frames(ws).mapNotNull { it["username"]?.jsonPrimitive?.content }.toSet()
        assertEquals(setOf(NYM), names, "never the @p, which Galène calls a spoof")

        // Our own arrival, as Galène lists it, is our row: the @p, muted as we are.
        line.handle(json.decodeFromString("""{"type":"user","kind":"add","id":"me","username":"$NYM"}"""))
        val row = (line.state.value as PartyState.Live).members.single()
        assertEquals(COMET, row.ship)
        assertTrue(row.muted, "our row reads our own mute")
    }

    @Test
    fun aPlanetStillSpeaksAsItsPatp() = onLine("~nec", "~nec") { line, ws ->
        line.handle(json.decodeFromString("""{"type":"joined","kind":"join","username":"~nec","rtcConfiguration":{"iceServers":[]}}"""))
        assertEquals("~nec", offer(ws)["username"]?.jsonPrimitive?.content)
        assertEquals(setOf("~nec"), frames(ws).mapNotNull { it["username"]?.jsonPrimitive?.content }.toSet())
    }

    @Test
    fun theServersWordBeatsTheToken() = onLine("~nec", "~nec") { line, ws ->
        // An opaque or stale token: whatever "joined" says is what Galène checks.
        line.handle(json.decodeFromString("""{"type":"joined","kind":"join","username":"$NYM","rtcConfiguration":{"iceServers":[]}}"""))
        assertEquals(NYM, offer(ws)["username"]?.jsonPrimitive?.content)
    }

    companion object {
        const val COMET = "~foppel-fitdyn-doznux-fithut--somdur-famdev-forpet-daplyd"
        const val NYM = ".renewed.erupt.prepare.ablate.outdid.demote.disburse.ensures.perfects.imbue.defames.involve"
    }

    // Trunk wire 16: a guest is keyed by its Galene username, never taken for a ship.
    @Test
    fun aGuestJoinsTheRosterByItsUsernameWithTheNameItTyped() = onLine("~nec", "~nec") { line, _ ->
        line.handle(json.decodeFromString("""{"type":"joined","kind":"join","username":"~nec","rtcConfiguration":{"iceServers":[]}}"""))
        line.handle(json.decodeFromString("""{"type":"user","kind":"add","id":"g1","username":"guest-3fa9c07b12de","data":{"name":"~zod"}}"""))
        val guest = withTimeout(5_000) {
            var m: PartyMember? = null
            while (m == null) { m = (line.state.value as? PartyState.Live)?.members?.firstOrNull { it.id == "g1" }; if (m == null) delay(10) }
            m
        }
        assertEquals("guest-3fa9c07b12de", guest.ship)
        assertEquals("~zod", guest.name)
        assertTrue(io.nisfeb.talon.comet.isGuestName(guest.ship))
        assertTrue(!io.nisfeb.talon.comet.isGuestName("~zod") && !io.nisfeb.talon.comet.isGuestName(NYM))
        // A listen link's anonymous listener is still counted, not listed.
        line.handle(json.decodeFromString("""{"type":"user","kind":"add","id":"l1","username":"listener"}"""))
        withTimeout(5_000) { while ((line.state.value as PartyState.Live).listeners != 1) delay(10) }
        assertTrue((line.state.value as PartyState.Live).members.none { it.id == "l1" })
    }
}
