package io.nisfeb.talon.call

import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Down links must be built with the ICE servers Galène hands out on
 * join — the same ones the up link gets.
 *
 * The bug this pins: the parsed servers lived in a local inside
 * `onJoined`, and `onRemoteOffer` — a separate callback — passed an
 * empty list instead. Publishing still worked, so a browser on the
 * public listen link heard every speaker, while nobody in the app
 * heard anything. The live E2E is gated behind TRUNK_E2E and never
 * ran in CI, so nothing caught it.
 */
class PartyLineIceTest {

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

    /** Records what every link was built with. */
    private class RecordingFactory : PeerLinkFactory {
        val calls = mutableListOf<Pair<List<IceServer>, Boolean>>()
        override fun create(iceServers: List<IceServer>, sendAudio: Boolean): PeerLink {
            calls += iceServers to sendAudio
            return FakeLink()
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** Galène's `joined` message, trimmed to the part we read. */
    private fun joined(): JsonObject = json.decodeFromString(
        """
        {
          "type": "joined",
          "kind": "join",
          "group": "talon",
          "rtcConfiguration": {
            "iceServers": [
              { "urls": ["stun:sfu.example:3478"] },
              {
                "urls": ["turn:sfu.example:3478"],
                "username": "galene",
                "credential": "s3cret"
              }
            ]
          }
        }
        """.trimIndent(),
    )

    private fun remoteOffer(id: String, who: String): JsonObject = json.decodeFromString(
        """{"type":"offer","id":"$id","username":"$who","sdp":"v=0 remote"}""",
    )

    @Test
    fun downLinksGetTheSameIceServersAsTheUpLink() = runTest {
        val factory = RecordingFactory()
        val line = PartyLine(HttpClient(), factory)

        line.onJoined(joined())
        line.onRemoteOffer(remoteOffer("stream-1", "~hapnyl-fotlyx"))

        val up = factory.calls.single { (_, sendAudio) -> sendAudio }
        val downs = factory.calls.filter { (_, sendAudio) -> !sendAudio }

        assertEquals(1, downs.size, "expected exactly one down link")
        assertTrue(up.first.isNotEmpty(), "up link got no ICE servers — join parse broke")
        assertEquals(
            up.first,
            downs.single().first,
            "down link built with different ICE than the up link — " +
                "it cannot traverse to the SFU and the call is silent in-app",
        )
    }

    @Test
    fun everySpeakerAfterTheFirstAlsoGetsThem() = runTest {
        val factory = RecordingFactory()
        val line = PartyLine(HttpClient(), factory)

        line.onJoined(joined())
        line.onRemoteOffer(remoteOffer("stream-1", "~hapnyl-fotlyx"))
        line.onRemoteOffer(remoteOffer("stream-2", "~ricsul-bilwyt"))

        val downs = factory.calls.filter { (_, sendAudio) -> !sendAudio }
        assertEquals(2, downs.size)
        for ((ice, _) in downs) {
            assertTrue(ice.isNotEmpty(), "a down link was built with no ICE servers")
        }
    }

    @Test
    fun galeneCredentialsSurviveTheParse() = runTest {
        val factory = RecordingFactory()
        PartyLine(HttpClient(), factory).onJoined(joined())

        val ice = factory.calls.single { (_, sendAudio) -> sendAudio }.first
        assertEquals(
            listOf("stun:sfu.example:3478", "turn:sfu.example:3478"),
            ice.map { it.url },
        )
        // The TURN entry is useless without its credentials.
        val turn = ice.single { it.url.startsWith("turn:") }
        assertEquals("galene", turn.user)
        assertEquals("s3cret", turn.cred)
    }
}
