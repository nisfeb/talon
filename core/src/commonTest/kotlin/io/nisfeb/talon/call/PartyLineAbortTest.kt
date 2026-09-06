package io.nisfeb.talon.call

import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Galène's `abort`: the server killed a stream.
 *
 * For OUR up stream that is the server saying our mic is gone, which
 * nothing else reports — the line must republish rather than sit in a
 * room nobody can hear us in. For a down stream the dead link must be
 * dropped, or a stale entry holds a dead connection forever.
 */
class PartyLineAbortTest {

    private class ScriptedLink : PeerLink {
        val stateFlow = MutableStateFlow(MediaState.Idle)
        override val state: StateFlow<MediaState> = stateFlow
        var closed = false
        override fun onLocalCandidate(callback: (IceCandidate) -> Unit) = Unit
        override suspend fun offer(): String = "v=0"
        override suspend fun answerTo(remoteSdp: String): String = "v=0"
        override suspend fun applyAnswer(remoteSdp: String) = Unit
        override fun addRemoteCandidate(candidate: IceCandidate) = Unit
        override fun setMuted(muted: Boolean) = Unit
        override fun close() { closed = true }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private fun joined() = json.decodeFromString<JsonObject>(
        """{"type":"joined","kind":"join","rtcConfiguration":{"iceServers":[]}}""",
    )
    private fun abort(id: String) = json.decodeFromString<JsonObject>(
        """{"type":"abort","id":"$id"}""",
    )
    private fun offer(id: String, who: String) = json.decodeFromString<JsonObject>(
        """{"type":"offer","id":"$id","username":"$who","sdp":"v=0"}""",
    )

    @Test
    fun anAbortOfOurUpStreamRepublishesTheMic() = runBlocking {
        val ups = mutableListOf<ScriptedLink>()
        val line = PartyLine(
            HttpClient(),
            links = { _, sendAudio -> ScriptedLink().also { if (sendAudio) ups += it } },
        )
        line.handle(joined())
        assertEquals(1, ups.size, "expected one up link after join")

        // The client mints the up stream's id each time it publishes
        // (and names the one it replaces), so abort the id it actually
        // offered rather than a fixed one.
        line.handle(abort(line.upId))
        assertEquals(2, ups.size, "the aborted mic was not republished")
        assertTrue(ups[0].closed, "the dead up link was left open")
    }

    @Test
    fun anAbortOfADownStreamDropsIt() = runBlocking {
        val downs = mutableListOf<ScriptedLink>()
        val line = PartyLine(
            HttpClient(),
            links = { _, sendAudio -> ScriptedLink().also { if (!sendAudio) downs += it } },
        )
        line.handle(offer("stream-1", "~zod"))
        assertEquals(1, downs.size)

        line.handle(abort("stream-1"))
        assertTrue(downs.single().closed, "the aborted down link was left open")

        // A stale entry would be reused here; a dropped one is rebuilt.
        line.handle(offer("stream-1", "~zod"))
        assertEquals(2, downs.size, "a re-offer must get a fresh link, not the dead one")
    }
}
