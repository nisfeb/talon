package io.nisfeb.talon.call

import io.ktor.client.HttpClient
import io.nisfeb.talon.util.nowMs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A dead microphone must not stay dead quietly.
 *
 * The up link is the one connection whose failure was invisible: down
 * links and the roster ride separately, so a member whose up link
 * failed heard everyone, appeared unmuted, and published nothing —
 * the "they unmuted and I can't hear them" report. The line must
 * notice a Failed up link and republish.
 */
class PartyLineUpRecoveryTest {

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

    @Test
    fun aFailedUpLinkIsRepublished() = runBlocking {
        val made = mutableListOf<ScriptedLink>()
        val line = PartyLine(
            HttpClient(),
            links = { _, sendAudio ->
                ScriptedLink().also { if (sendAudio) made += it }
            },
            upRetryBaseMs = 5,
        )
        // Drive the joined message by hand, as the tones tests do —
        // join() would start the real network pump, whose failure
        // against a dummy URL tears the line down mid-test.
        line.handle(joined())
        assertEquals(1, made.size, "expected one up link after join")

        made[0].stateFlow.value = MediaState.Failed
        // Real dispatchers: poll rather than trust a virtual clock the
        // line's own scope never sees.
        withTimeout(5_000) { while (made.size < 2) delay(10) }
        assertTrue(made[0].closed, "the dead link was left open")
    }

    @Test
    fun retriesAreBoundedSoAHopelessNetworkDoesNotLoop() = runBlocking {
        val made = mutableListOf<ScriptedLink>()
        val line = PartyLine(
            HttpClient(),
            links = { _, sendAudio ->
                ScriptedLink().also {
                    if (sendAudio) {
                        made += it
                        // every up link fails immediately
                        it.stateFlow.value = MediaState.Failed
                    }
                }
            },
            upRetryBaseMs = 5,
        )
        line.handle(joined())
        // Wait for the retry ladder to go quiet: the gaps are 5/10/15ms,
        // so a count unchanged for 300ms means no republish is in
        // flight. An unbounded loop keeps the count moving until the
        // 2s cap and then fails the exact assertion below.
        val deadline = nowMs() + 2_000
        var seen = made.size
        var stableSince = nowMs()
        while (nowMs() < deadline) {
            delay(20)
            if (made.size != seen) {
                seen = made.size
                stableSince = nowMs()
            } else if (nowMs() - stableSince >= 300) break
        }
        // Deterministic: the first link plus exactly the field-default
        // budget of 3 retries (join() was never called, so nothing
        // reset it, and no link ever went Live to replenish it).
        assertEquals(4, made.size, "expected 1 initial + 3 retries, got ${made.size}")
    }
}
