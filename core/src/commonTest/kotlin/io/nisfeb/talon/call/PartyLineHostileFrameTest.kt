package io.nisfeb.talon.call

import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Frames a party line has to survive from an SFU it does not control:
 * shapes it never expected, and moderation from members who have no
 * business issuing it.
 */
class PartyLineHostileFrameTest {

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
    private fun msg(raw: String): JsonObject = json.decodeFromString(raw)

    @Test
    fun aMuteRequestFromAnOrdinaryMemberIsIgnored() = runTest {
        val l = line()
        // Every speaker holds Galène's `message` right, so without the
        // privileged check any member could mute the whole room on a loop.
        l.handle(msg("""{"type":"usermessage","kind":"mute","source":"c9","dest":""}"""))
        assertFalse(l.isSelfMuted(), "a non-operator must not be able to mute us")
    }

    @Test
    fun aMuteRequestFromAnOperatorIsHonoured() = runTest {
        val l = line()
        l.handle(
            msg("""{"type":"usermessage","kind":"mute","source":"c1","dest":"","privileged":true}"""),
        )
        assertTrue(l.isSelfMuted(), "an operator's mute is what this feature is for")
    }

    @Test
    fun anObjectValuedNoticeDoesNotKillTheLine() = runTest {
        val l = line()
        // A Galène filetransfer puts an object where a notice string is
        // assumed, and a clearchat puts null; .jsonPrimitive throws on
        // both, which used to escape the pump and fail the whole line.
        l.handle(msg("""{"type":"usermessage","kind":"filetransfer","value":{"id":"x"}}"""))
        l.handle(msg("""{"type":"usermessage","kind":"clearchat","value":null}"""))
        assertTrue(
            l.state.value !is PartyState.Failed,
            "an unexpected usermessage shape must not fail the line",
        )
    }
}
