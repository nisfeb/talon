package io.nisfeb.talon.urbit

import com.sun.net.httpserver.HttpServer
import io.nisfeb.talon.util.createAppHttpClient
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression guard for the "SSE delivers no events" bug: eyre held the
 * channel GET open but Ktor's SSE plugin never surfaced the frames, so
 * the 90s watchdog reconnected forever, no optimistic post ever got its
 * echo (grey twins stranded), and each reconnect's re-scry re-added the
 * post as a duplicate row.
 *
 * Drives the real UrbitChannel.events() against a local server that
 * streams eyre-shaped frames (heartbeat comment, id + data) with the
 * connection held open, and asserts frames arrive live and parsed.
 */
class UrbitChannelSseTest {

    @Test
    fun eventsStreamsFramesLiveFromHeldOpenChannel() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        // events() GETs "$baseUrl/~/channel/<uid>"; match the prefix.
        server.createContext("/~/channel/") { ex ->
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, 0) // chunked / streaming, held open
            val out = ex.responseBody
            runCatching {
                out.write(": open\n\n".toByteArray()); out.flush() // eyre-style heartbeat comment
                for (n in 1..8) {
                    out.write("id: $n\ndata: {\"ok\":$n}\n\n".toByteArray())
                    out.flush()
                    Thread.sleep(150)
                }
            }
            runCatching { out.close() }
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        val http = createAppHttpClient()
        val ch = UrbitChannel(http, base, "zod")

        val got = ConcurrentLinkedQueue<UrbitEvent>()
        withTimeoutOrNull(1_200) {
            val job = launch { ch.events().collect { got.add(it); if (got.size >= 4) throw kotlinx.coroutines.CancellationException("enough") } }
            job.join()
        }

        println("SSE-EVENTS frames=${got.size} first=${got.firstOrNull()?.id}:${got.firstOrNull()?.body}")
        server.stop(0)
        http.close()

        // If the reader buffered (the bug), got would be ~0 within 1.2s.
        assertTrue(got.size >= 4, "events() should surface live frames, got ${got.size}")
        val first = got.first()
        assertEquals(1L, first.id, "frame id parsed wrong")
        assertEquals("{\"ok\":1}", first.body.toString(), "frame data parsed wrong")
    }
}
