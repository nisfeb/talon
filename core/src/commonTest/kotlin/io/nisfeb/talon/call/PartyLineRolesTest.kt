package io.nisfeb.talon.call

import io.ktor.client.HttpClient
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Job
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Wire 5 on the SFU side: a listener's line never captures a mic, a
 * mid-line permission change moves the mic honestly in both
 * directions, and op moderation reaches every connection of the
 * target ship. Real dispatchers + polling, as the recovery tests do —
 * the line's scope never sees a virtual clock.
 */
class PartyLineRolesTest {

    private class ScriptedLink : PeerLink {
        val stateFlow = MutableStateFlow(MediaState.Idle)
        override val state: StateFlow<MediaState> = stateFlow
        var closed = false
        var lastMuted: Boolean? = null
        override fun onLocalCandidate(callback: (IceCandidate) -> Unit) = Unit
        override suspend fun offer(): String = "v=0"
        override suspend fun answerTo(remoteSdp: String): String = "v=0"
        override suspend fun applyAnswer(remoteSdp: String) = Unit
        override fun addRemoteCandidate(candidate: IceCandidate) = Unit
        override fun setMuted(muted: Boolean) { lastMuted = muted }
        override fun close() { closed = true }
    }

    /** Records what the line says to the SFU; hears nothing back. */
    private class RecordingWs : WebSocketSession {
        val sent = mutableListOf<String>()
        override val coroutineContext: CoroutineContext = Job()
        override var masking = false
        override var maxFrameSize = Long.MAX_VALUE
        override val incoming: ReceiveChannel<Frame> = Channel()
        override val outgoing: SendChannel<Frame> = Channel(Channel.UNLIMITED)
        override val extensions: List<WebSocketExtension<*>> = emptyList()
        override suspend fun send(frame: Frame) {
            (frame as? Frame.Text)?.let { synchronized(sent) { sent += it.readText() } }
        }
        override suspend fun flush() = Unit
        @Deprecated("unused in tests")
        override fun terminate() = Unit
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun joined(perms: List<String>? = null) = json.decodeFromString<JsonObject>(
        """{"type":"joined","kind":"join","rtcConfiguration":{"iceServers":[]}""" +
            (perms?.let { ""","permissions":[${it.joinToString(",") { p -> "\"$p\"" }}]""" } ?: "") +
            "}",
    )

    private fun change(perms: List<String>) = json.decodeFromString<JsonObject>(
        """{"type":"joined","kind":"change",""" +
            """"permissions":[${perms.joinToString(",") { "\"$it\"" }}]}""",
    )

    private fun userAdd(id: String, who: String) = json.decodeFromString<JsonObject>(
        """{"type":"user","kind":"add","id":"$id","username":"$who"}""",
    )

    private fun live(l: PartyLine) = l.state.value as PartyState.Live

    private fun lineWith(ups: MutableList<ScriptedLink>) = PartyLine(
        HttpClient(),
        links = { _, sendAudio -> ScriptedLink().also { if (sendAudio) ups += it } },
    )

    /** (dest, kind) of every useraction frame recorded so far. */
    private fun userActions(ws: RecordingWs): List<Pair<String, String>> =
        synchronized(ws.sent) { ws.sent.toList() }.mapNotNull { raw ->
            val o = json.parseToJsonElement(raw).jsonObject
            if (o["type"]?.jsonPrimitive?.content != "useraction") return@mapNotNull null
            (o["dest"]?.jsonPrimitive?.content ?: "") to
                (o["kind"]?.jsonPrimitive?.content ?: "")
        }

    @Test
    fun aListenerJoinOpensNoMic() = runBlocking {
        val ups = mutableListOf<ScriptedLink>()
        val line = lineWith(ups)
        line.handle(joined(perms = listOf("message")))
        assertEquals(0, ups.size, "a listener must never create a sendAudio link")
        assertFalse(live(line).canSpeak)
        assertFalse(live(line).ops)
    }

    @Test
    fun theJwtSeedHoldsWhenTheServerSaysNothing() = runBlocking {
        // A pre-1.1 Galène echoes no permissions on join; the token's
        // word (seeded at join()) must then stand.
        val ups = mutableListOf<ScriptedLink>()
        val line = lineWith(ups)
        line.canSpeak = false
        line.handle(joined())
        assertEquals(0, ups.size, "the JWT said listener; no permissions echo overrides that")
        assertFalse(live(line).canSpeak)
    }

    @Test
    fun serverEchoedPermissionsBeatTheJwt() = runBlocking {
        // The host can edit roles after minting our token; the
        // server's join echo is the fresher truth.
        val ups = mutableListOf<ScriptedLink>()
        val line = lineWith(ups)
        line.canSpeak = false
        line.handle(joined(perms = listOf("op", "present", "message")))
        assertEquals(1, ups.size, "present granted on join must publish the mic")
        assertTrue(live(line).canSpeak)
        assertTrue(live(line).ops)
    }

    @Test
    fun aChangeRevokingPresentClosesTheMic() = runBlocking {
        val ups = mutableListOf<ScriptedLink>()
        val line = lineWith(ups)
        line.handle(joined(perms = listOf("present", "message")))
        assertEquals(1, ups.size)

        line.handle(change(listOf("message")))
        assertTrue(ups[0].closed, "the up link must close locally, whatever the server does")
        assertFalse(live(line).canSpeak)
        assertEquals(MediaState.Idle, live(line).media, "a closed mic must not report old media")
        assertEquals(1, ups.size, "nothing may republish a revoked mic")
    }

    @Test
    fun aChangeGrantingPresentRepublishes() = runBlocking {
        val ups = mutableListOf<ScriptedLink>()
        val line = lineWith(ups)
        line.handle(joined(perms = listOf("message")))
        assertEquals(0, ups.size)

        line.handle(change(listOf("present", "message")))
        assertEquals(1, ups.size, "present granted mid-line must stand the mic up")
        assertTrue(live(line).canSpeak)
    }

    @Test
    fun aRestoreAfterRevokeComesBackMuted() = runBlocking {
        // The user was talking when an op revoked them. A restore —
        // maybe hours later — must re-arm the join-muted rule, not
        // resume broadcasting whatever room they're sitting in.
        val ups = mutableListOf<ScriptedLink>()
        val line = lineWith(ups)
        line.handle(joined(perms = listOf("present", "message")))
        line.setMuted(false)
        assertEquals(false, ups[0].lastMuted)

        line.handle(change(listOf("message")))
        line.handle(change(listOf("present", "message")))
        assertEquals(2, ups.size)
        assertEquals(true, ups[1].lastMuted, "a restored mic must come back muted")
    }

    @Test
    fun aServerAbortDoesNotResurrectAListenersMic() = runBlocking {
        // abort of our up id republishes for a speaker; for a
        // listener there is nothing honest to republish.
        val ups = mutableListOf<ScriptedLink>()
        val line = lineWith(ups)
        line.upId = "up-test"
        line.handle(joined(perms = listOf("message")))
        line.handle(json.decodeFromString("""{"type":"abort","id":"up-test"}"""))
        assertEquals(0, ups.size)
    }

    @Test
    fun revokeSpeakingHitsEveryConnectionOfTheShip() = runBlocking {
        val line = lineWith(mutableListOf())
        val ws = RecordingWs()
        line.session = ws
        line.handle(userAdd("c1", "~zod"))
        line.handle(userAdd("c2", "~zod"))
        line.handle(userAdd("c3", "~bus"))

        line.revokeSpeaking("~zod")
        withTimeout(5_000) { while (userActions(ws).size < 2) delay(10) }
        assertEquals(
            setOf("c1" to "unpresent", "c2" to "unpresent"),
            userActions(ws).toSet(),
            "both of ~zod's connections, and only ~zod's",
        )

        line.restoreSpeaking("~zod")
        withTimeout(5_000) {
            while (userActions(ws).count { it.second == "present" } < 2) delay(10)
        }
        assertEquals(
            setOf("c1" to "present", "c2" to "present"),
            userActions(ws).filter { it.second == "present" }.toSet(),
        )
        assertTrue(
            userActions(ws).none { it.first == "c3" },
            "~bus was never the target",
        )
    }

    /** Every ADMIN_MUTE_KIND broadcast (username -> muted) recorded. */
    private fun adminMutes(ws: RecordingWs): List<Pair<String, Boolean>> =
        synchronized(ws.sent) { ws.sent.toList() }.mapNotNull { raw ->
            val o = json.parseToJsonElement(raw).jsonObject
            if (o["type"]?.jsonPrimitive?.content != "usermessage") return@mapNotNull null
            if (o["kind"]?.jsonPrimitive?.content != PartyLine.ADMIN_MUTE_KIND) return@mapNotNull null
            // The subject rides `target`; `username` is the sender, as
            // it is in every other broadcast.
            (o["target"]?.jsonPrimitive?.content ?: "") to
                (o["value"]?.jsonPrimitive?.content == "true")
        }

    /** The `username` field of every admin-mute frame sent. */
    private fun adminMuteSenders(ws: RecordingWs): List<String> =
        synchronized(ws.sent) { ws.sent.toList() }.mapNotNull { raw ->
            val o = json.parseToJsonElement(raw).jsonObject
            if (o["kind"]?.jsonPrimitive?.content != PartyLine.ADMIN_MUTE_KIND) return@mapNotNull null
            o["username"]?.jsonPrimitive?.content
        }

    @Test
    fun revokingSpeakingMarksTheMemberAndBroadcastsIt() = runBlocking {
        val line = lineWith(mutableListOf())
        val ws = RecordingWs()
        line.session = ws
        line.ops = true
        line.handle(joined(perms = listOf("op", "present", "message")))
        line.handle(userAdd("c1", "~zod"))
        line.handle(userAdd("c2", "~bus"))

        line.revokeSpeaking("~zod")
        // The op's own roster marks ~zod immediately, before any echo.
        val zod = live(line).members.first { it.ship == "~zod" }
        assertTrue(zod.mutedByAdmin, "the muted member is marked for the op at once")
        assertTrue(live(line).members.first { it.ship == "~bus" }.mutedByAdmin.not())

        withTimeout(5_000) { while (adminMutes(ws).none { it == "~zod" to true }) delay(10) }
        // Never claim to be the person being muted: an SFU that
        // validates `username` drops the operator's socket, and one that
        // rewrites it retargets the mute at the operator.
        assertTrue(
            adminMuteSenders(ws).none { it == "~zod" },
            "the broadcast must not send the target as the sender",
        )

        line.restoreSpeaking("~zod")
        assertTrue(live(line).members.first { it.ship == "~zod" }.mutedByAdmin.not())
        withTimeout(5_000) { while (adminMutes(ws).none { it == "~zod" to false }) delay(10) }
    }

    @Test
    fun anAdminMuteBroadcastMarksTheMemberForEveryone() = runBlocking {
        // A non-op receiving the broadcast still marks the member —
        // this is how the mark reaches clients Galène never told.
        val line = lineWith(mutableListOf())
        val ws = RecordingWs()
        line.session = ws
        line.handle(joined(perms = listOf("present", "message")))
        line.handle(userAdd("c1", "~zod"))

        line.handle(
            json.decodeFromString(
                """{"type":"usermessage","kind":"${PartyLine.ADMIN_MUTE_KIND}",""" +
                    """"username":"~zod","value":"true"}""",
            ),
        )
        assertTrue(live(line).members.first { it.ship == "~zod" }.mutedByAdmin)

        line.handle(
            json.decodeFromString(
                """{"type":"usermessage","kind":"${PartyLine.ADMIN_MUTE_KIND}",""" +
                    """"username":"~zod","value":"false"}""",
            ),
        )
        assertTrue(live(line).members.first { it.ship == "~zod" }.mutedByAdmin.not())
    }
}
