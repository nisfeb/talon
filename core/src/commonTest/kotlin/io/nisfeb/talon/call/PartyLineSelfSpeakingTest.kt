package io.nisfeb.talon.call

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A speaking dot on your own row.
 *
 * The point of it is telling "nobody is talking" apart from "my
 * microphone is dead", so the two cases that matter are that it lights
 * when the mic hears something and that it stays dark while muted —
 * where the level still moves, because muting disables the track, not
 * the capture.
 */
class PartyLineSelfSpeakingTest {

    private class LeveledLink(private val local: Float) : PeerLink {
        override val state: StateFlow<MediaState> = MutableStateFlow(MediaState.Live)
        override fun onLocalCandidate(callback: (IceCandidate) -> Unit) = Unit
        override suspend fun offer(): String = "v=0"
        override suspend fun answerTo(remoteSdp: String): String = "v=0"
        override suspend fun applyAnswer(remoteSdp: String) = Unit
        override fun addRemoteCandidate(candidate: IceCandidate) = Unit
        override fun setMuted(muted: Boolean) = Unit
        override fun localAudioLevel(): Float = local
        override fun close() = Unit
    }

    private val json = Json { ignoreUnknownKeys = true }
    private fun joined() = json.decodeFromString<JsonObject>(
        """{"type":"joined","kind":"join","rtcConfiguration":{"iceServers":[]}}""",
    )
    private fun userAdd(id: String, who: String) = json.decodeFromString<JsonObject>(
        """{"type":"user","kind":"add","id":"$id","username":"$who"}""",
    )

    /** A line whose up link hears a loud (0.30) microphone, joined as
     *  ~nec, unmuted, with our own roster row present. join() runs
     *  against a server that never answers, which is the one way to
     *  set the line's own ship without the pump failing and tearing
     *  everything down mid-test. */
    private suspend fun liveLine(): PartyLine {
        val http = HttpClient(MockEngine { awaitCancellation() })
        val line = PartyLine(http, links = { _, _ -> LeveledLink(0.30f) })
        line.join(TrunkTicket("room", "http://sfu.test/x/", "tok"), "~nec")
        line.handle(joined())
        line.handle(userAdd("c1", "~nec"))
        line.setMuted(false) // join() starts muted by design
        return line
    }

    private fun ourRow(l: PartyLine): PartyMember? =
        (l.state.value as? PartyState.Live)?.members?.firstOrNull { it.ship == "~nec" }

    // Real dispatchers and real polling (SPEAKING_POLL_MS is 250):
    // the line's level poll runs on its own ioDispatcher scope, which
    // a virtual clock never reaches.
    private suspend fun awaitSpeaking(l: PartyLine, want: Boolean) =
        withTimeout(3_000) {
            while (ourRow(l)?.speaking != want) delay(20)
        }

    @Test
    fun aLoudMicMarksOurOwnRowSpeaking() = runBlocking {
        val line = liveLine()
        awaitSpeaking(line, true)
    }

    @Test
    fun mutingKeepsOurDotDarkEvenWhileTheLevelIsLoud() = runBlocking {
        val line = liveLine()
        awaitSpeaking(line, true)

        // The capture still reports 0.30 — only the track is disabled —
        // so a dot that lights up now says the opposite of the truth.
        line.setMuted(true)
        awaitSpeaking(line, false)
        assertEquals(false, ourRow(line)?.speaking)
    }

    @Test
    fun theDefaultIsNullSoAPlatformCanDeclineToAnswer() {
        // Every leaf that hasn't implemented it must read as "unknown"
        // rather than "silent", or a platform without the statistic
        // would render everyone permanently quiet.
        val bare = object : PeerLink {
            override val state: StateFlow<MediaState> = MutableStateFlow(MediaState.Idle)
            override fun onLocalCandidate(callback: (IceCandidate) -> Unit) = Unit
            override suspend fun offer(): String = ""
            override suspend fun answerTo(remoteSdp: String): String = ""
            override suspend fun applyAnswer(remoteSdp: String) = Unit
            override fun addRemoteCandidate(candidate: IceCandidate) = Unit
            override fun setMuted(muted: Boolean) = Unit
            override fun close() = Unit
        }
        assertNull(bare.localAudioLevel())
        assertNull(bare.audioLevel())
    }
}
