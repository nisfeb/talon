package io.nisfeb.talon.call

import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Who has their mic off, as the roster reports it.
 *
 * Galène cannot answer this — muting disables the local track, so a
 * muted speaker and a silent one are identical on the wire. Clients
 * broadcast it to each other as an application-specific usermessage,
 * which Galène relays untouched.
 */
class PartyLineMuteTest {

    private class FakeLink : PeerLink {
        override val state: StateFlow<MediaState> = MutableStateFlow(MediaState.Idle)
        override fun onLocalCandidate(callback: (IceCandidate) -> Unit) = Unit
        override suspend fun offer(): String = "v=0 fake-offer"
        override suspend fun answerTo(remoteSdp: String): String = "v=0 fake-answer"
        override suspend fun applyAnswer(remoteSdp: String) = Unit
        override fun addRemoteCandidate(candidate: IceCandidate) = Unit
        override fun setMuted(muted: Boolean) = Unit
        override fun close() = Unit
    }

    private val json = Json { ignoreUnknownKeys = true }
    private fun line() = PartyLine(HttpClient(), links = { _, _ -> FakeLink() })

    private fun userAdd(id: String, who: String): JsonObject = json.decodeFromString(
        """{"type":"user","kind":"add","id":"$id","username":"$who"}""",
    )

    private fun muteMsg(who: String, muted: Boolean): JsonObject = json.decodeFromString(
        """{"type":"usermessage","kind":"${PartyLine.MUTE_KIND}",
            "source":"c1","dest":"","username":"$who","value":$muted}""",
    )

    private fun members(l: PartyLine) =
        (l.state.value as? PartyState.Live)?.members.orEmpty()

    @Test
    fun aBroadcastMarksThatPersonMuted() = runTest {
        val l = line()
        l.handle(userAdd("c1", "~hapnyl-fotlyx"))
        l.handle(userAdd("c2", "~ricsul-bilwyt"))
        l.handle(muteMsg("~hapnyl-fotlyx", true))

        val byShip = members(l).associateBy { it.ship }
        assertTrue(byShip["~hapnyl-fotlyx"]!!.muted, "the broadcast should mark them muted")
        assertFalse(byShip["~ricsul-bilwyt"]!!.muted, "nobody else should be affected")
    }

    @Test
    fun unmutingClearsIt() = runTest {
        val l = line()
        l.handle(userAdd("c1", "~hapnyl-fotlyx"))
        l.handle(muteMsg("~hapnyl-fotlyx", true))
        assertTrue(members(l).single().muted)

        l.handle(muteMsg("~hapnyl-fotlyx", false))
        assertFalse(members(l).single().muted, "unmute must clear the flag, not latch it")
    }

    @Test
    fun leavingForgetsTheirMuteState() = runTest {
        // Otherwise a rejoin inherits a stale flag: they come back with
        // a live mic and everyone renders them muted.
        val l = line()
        l.handle(userAdd("c1", "~hapnyl-fotlyx"))
        l.handle(muteMsg("~hapnyl-fotlyx", true))
        l.handle(json.decodeFromString("""{"type":"user","kind":"delete","id":"c1"}"""))
        l.handle(userAdd("c2", "~hapnyl-fotlyx"))

        assertFalse(
            members(l).single().muted,
            "a rejoin must not inherit the mute state of the old connection",
        )
    }

    @Test
    fun anUnrelatedUsermessageIsIgnored() = runTest {
        // Galène relays other kinds; they must not be read as mute.
        val l = line()
        l.handle(userAdd("c1", "~hapnyl-fotlyx"))
        l.handle(
            json.decodeFromString(
                """{"type":"usermessage","kind":"chat","source":"c1",
                    "dest":"","username":"~hapnyl-fotlyx","value":"true"}""",
            ),
        )
        assertFalse(members(l).single().muted)
    }

    @Test
    fun aGaleneModerationMuteTakesOurMicOff() = runTest {
        // Galène's own /mute moderation arrives as a usermessage of
        // kind "mute" (unlike MUTE_KIND, which is Talon's gossip about
        // other people's mics). Every other client honours it; being
        // the one that keeps broadcasting makes /mute useless against
        // a Talon participant.
        val l = line()
        l.handle(userAdd("c1", "~hapnyl-fotlyx"))
        assertFalse((l.state.value as PartyState.Live).muted)

        l.handle(
            json.decodeFromString(
                """{"type":"usermessage","kind":"mute","source":"op",
                    "dest":"","username":"~hapnyl-fotlyx"}""",
            ),
        )
        assertTrue(
            (l.state.value as PartyState.Live).muted,
            "an operator mute request must take our mic off",
        )
    }

    @Test
    fun listenersAreNotRosterRows() = runTest {
        // Anonymous listeners don't carry a @p, so they must not appear
        // as people with mic state.
        val l = line()
        l.handle(userAdd("c1", "~hapnyl-fotlyx"))
        l.handle(userAdd("c2", "listener"))

        assertEquals(listOf("~hapnyl-fotlyx"), members(l).map { it.ship })
        assertEquals(1, (l.state.value as PartyState.Live).listeners)
    }
}
