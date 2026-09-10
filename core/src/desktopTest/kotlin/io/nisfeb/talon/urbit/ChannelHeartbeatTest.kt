package io.nisfeb.talon.urbit

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Eyre sends an SSE comment (`:`) every ~25 seconds whether or not the
 * ship has news. Talon's reader discarded them and the session watchdog
 * measured time since the last *event*, so every idle ship looked dead
 * after 90 seconds: the client force-reconnected and re-ran its whole
 * bootstrap around the clock. Heartbeats must count as stream activity.
 */
class ChannelHeartbeatTest {
    private fun channelOver(body: String): UrbitChannel {
        val engine = MockEngine { req ->
            if (req.method.value == "GET") {
                respond(
                    ByteReadChannel(body),
                    HttpStatusCode.OK,
                    headersOf("Content-Type", "text/event-stream"),
                )
            } else {
                respond("", HttpStatusCode.NoContent)
            }
        }
        return UrbitChannel(HttpClient(engine), "http://ship.test", "~zod")
    }

    @Test
    fun `heartbeats keep the stream live without producing events`() = runBlocking {
        // One real frame, then nothing but heartbeats.
        val ch = channelOver(
            "id: 0\ndata: {\"ok\":\"ok\",\"id\":1,\"response\":\"subscribe\"}\n\n" +
                ":\n:\n:\n:\n",
        )
        assertEquals(Long.MAX_VALUE, ch.streamIdleMs, "quiet before it connects")
        val first = ch.events().first()
        assertEquals(0L, first.id)
        assertTrue(ch.streamIdleMs < 5_000, "the stream counts as live: ${ch.streamIdleMs}ms")
    }

    @Test
    fun `a stream of heartbeats alone still counts as live`() = runBlocking {
        val ch = channelOver(":\n:\n:\n")
        // No event ever arrives; the flow completes when the body ends.
        val events = mutableListOf<UrbitEvent>()
        runCatching { ch.events().collect { events.add(it) } }
        assertEquals(0, events.size, "heartbeats are not events")
        assertTrue(ch.streamIdleMs < 5_000, "but they are activity: ${ch.streamIdleMs}ms")
    }
}
