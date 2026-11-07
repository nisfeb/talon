package io.nisfeb.talon.urbit

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A stream opened again on a channel resumes it, as eyre allows for twelve
 * hours after a stream drops: it names the last event applied, and what
 * eyre replays up to it is skipped.
 */
class UrbitChannelResumeTest {

    private fun frames(vararg ids: Long) = ids.joinToString("") { "id: $it\ndata: {\"n\":$it}\n\n" }

    @Test
    fun `a stream opened again starts after the last event applied and skips the replay`() = runBlocking<Unit> {
        val asked = CopyOnWriteArrayList<String>()
        var gets = 0
        val http = HttpClient(MockEngine { req ->
            asked += req.headers["Last-Event-ID"] ?: "none"
            gets++
            // Eyre's note: what it replays may include events heard before.
            val body = if (gets == 1) frames(1, 2) else frames(2, 3)
            respond(ByteReadChannel(body), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        })
        val ch = UrbitChannel(http, "https://ship.test", "~zod")
        ch.events().toList().forEach { ch.applied(it.id!!) }
        val second = ch.events().toList()
        assertEquals(listOf("none", "2"), asked)
        assertEquals(listOf(3L), second.map { it.id })
    }

    @Test
    fun `a channel the ship reaped says so, and is not asked for again`() = runBlocking<Unit> {
        var gets = 0
        val http = HttpClient(MockEngine { gets++; respond("", HttpStatusCode.NotFound) })
        val ch = UrbitChannel(http, "https://ship.test", "~zod")
        assertFailsWith<ChannelGone> { ch.events().toList() }
        assertTrue(ch.gone)
        assertFailsWith<ChannelGone> { ch.events().toList() }
        assertEquals(1, gets)
    }

    @Test
    fun `a deleted channel is never resumed`() = runBlocking<Unit> {
        var gets = 0
        val http = HttpClient(MockEngine { req ->
            if (req.method.value == "GET") gets++
            respond("", HttpStatusCode.NoContent)
        })
        val ch = UrbitChannel(http, "https://ship.test", "~zod")
        ch.delete()
        assertTrue(ch.gone)
        assertFailsWith<ChannelGone> { ch.events().toList() }
        assertEquals(0, gets)
    }

    @Test
    fun `a channel given up twice is ended on the ship once`() = runBlocking<Unit> {
        val puts = CopyOnWriteArrayList<String>()
        val http = HttpClient(MockEngine { req ->
            if (req.method.value == "PUT") puts += String(req.body.toByteArray())
            respond("", HttpStatusCode.NoContent)
        })
        val ch = UrbitChannel(http, "https://ship.test", "~zod")
        ch.delete()
        ch.delete()
        assertEquals(1, puts.size, "$puts")
        assertTrue("\"action\":\"delete\"" in puts.single())
    }

    // Eyre ties a channel to the login that made it: anyone else gets 403,
    // on the stream and on a PUT, for good.
    @Test
    fun `a stream refused to this login says so, and is not asked for again`() = runBlocking<Unit> {
        var gets = 0
        val http = HttpClient(MockEngine { gets++; respond("", HttpStatusCode.Forbidden) })
        val ch = UrbitChannel(http, "https://ship.test", "~zod")
        val e = assertFailsWith<ChannelGone> { ch.events().toList() }
        assertTrue(e.forbidden && ch.gone)
        assertFailsWith<ChannelGone> { ch.events().toList() }
        assertEquals(1, gets)
    }

    @Test
    fun `a PUT refused to this login ends the open stream`() = runBlocking<Unit> {
        val held = kotlinx.coroutines.CompletableDeferred<Unit>()
        val http = HttpClient(MockEngine { req ->
            if (req.method.value == "PUT") return@MockEngine respond("", HttpStatusCode.Forbidden)
            // The stream stays open, quiet, until the test is over.
            val body = io.ktor.utils.io.ByteChannel(autoFlush = true)
            launch { held.await(); body.close() }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        })
        val ch = UrbitChannel(http, "https://ship.test", "~zod")
        val stream = async { runCatching { ch.events().toList() }.exceptionOrNull() }
        kotlinx.coroutines.delay(200)
        val put = assertFailsWith<ChannelGone> { ch.subscribe("chat", "/v4") }
        assertTrue(put.forbidden && ch.gone)
        val ended = kotlinx.coroutines.withTimeout(5_000) { stream.await() }
        held.complete(Unit)
        assertTrue(ended is ChannelGone && ended.forbidden, "the stream ended with it: $ended")
    }

    // A stream asked for after the refusal said only "gone", and the
    // session took that as a channel reaped: a new one at once, and on a
    // lapsed login every two seconds (review A5-18).
    @Test
    fun `a stream asked for after a refused PUT says it was refused`() = runBlocking<Unit> {
        var gets = 0
        val http = HttpClient(MockEngine { req ->
            if (req.method.value == "GET") gets++
            respond("", HttpStatusCode.Forbidden)
        })
        val ch = UrbitChannel(http, "https://ship.test", "~zod")
        assertFailsWith<ChannelGone> { ch.subscribe("chat", "/v4") }
        val e = assertFailsWith<ChannelGone> { ch.events().toList() }
        assertTrue(e.forbidden, "refused, not reaped")
        assertEquals(0, gets, "not asked: it is gone")
    }

    // ─── a PUT between streams ─────────────────────────────────────

    // Eyre makes a channel on any PUT to one it does not hold, empty: a PUT
    // that beat the resume's GET to a reaped channel made it again, the GET
    // found it, and nothing came down it, with nothing to say so.

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.event(body: String) = respond(ByteReadChannel(body), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))

    @Test
    fun `a PUT between streams waits for the next, and on a channel the ship reaped fails as gone, never making it again`() = runBlocking<Unit> {
        val puts = CopyOnWriteArrayList<String>()
        var gets = 0
        val http = HttpClient(MockEngine { req ->
            if (req.method.value == "PUT") {
                puts += String(req.body.toByteArray())
                return@MockEngine respond("", HttpStatusCode.NoContent)
            }
            gets++
            if (gets == 1) event(frames(1)) else respond("", HttpStatusCode.NotFound)
        })
        val ch = UrbitChannel(http, "https://ship.test", "~zod")
        ch.events().toList()
        val ack = async { runCatching { ch.ack(1) }.exceptionOrNull() }
        kotlinx.coroutines.delay(300)
        assertEquals(emptyList(), puts.toList(), "sent before the next stream was answered")
        assertFailsWith<ChannelGone> { ch.events().toList() }
        assertTrue(ack.await() is ChannelGone, "${ack.await()}")
        assertEquals(emptyList(), puts.toList(), "a PUT to the reaped channel made it again")
    }

    @Test
    fun `a PUT between streams goes once the next is answered, and one on an open stream at once`() = runBlocking<Unit> {
        val order = CopyOnWriteArrayList<String>()
        val held = kotlinx.coroutines.CompletableDeferred<Unit>()
        var gets = 0
        val http = HttpClient(MockEngine { req ->
            order += req.method.value
            if (req.method.value == "PUT") return@MockEngine respond("", HttpStatusCode.NoContent)
            gets++
            if (gets == 1) return@MockEngine event(frames(1))
            val body = io.ktor.utils.io.ByteChannel(autoFlush = true)
            launch { held.await(); body.close() }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        })
        val ch = UrbitChannel(http, "https://ship.test", "~zod")
        ch.events().toList()
        val ack = async { ch.ack(1) }
        kotlinx.coroutines.delay(300)
        assertEquals(listOf("GET"), order.toList())
        val stream = launch { ch.events().collect {} }
        kotlinx.coroutines.withTimeout(5_000) { ack.await() }
        assertEquals(listOf("GET", "GET", "PUT"), order.toList())
        val started = System.currentTimeMillis()
        ch.ack(2)
        assertTrue(System.currentTimeMillis() - started < 1_000, "an open stream's PUT waited")
        assertEquals(listOf("GET", "GET", "PUT", "PUT"), order.toList())
        held.complete(Unit)
        stream.cancel()
    }

    @Test
    fun `with no stream asked for, a PUT between streams goes after the wait, as before`() = runBlocking<Unit> {
        val puts = CopyOnWriteArrayList<String>()
        val http = HttpClient(MockEngine { req ->
            if (req.method.value == "PUT") puts += String(req.body.toByteArray())
            if (req.method.value == "PUT") respond("", HttpStatusCode.NoContent) else event(frames(1))
        })
        val ch = UrbitChannel(http, "https://ship.test", "~zod", streamAnswerWaitMs = 300)
        ch.events().toList()
        val started = System.currentTimeMillis()
        ch.ack(1)
        assertTrue(System.currentTimeMillis() - started >= 300, "it did not wait")
        assertEquals(1, puts.size)
    }

    @Test
    fun `before its first stream a PUT goes at once, since eyre makes the channel on it`() = runBlocking<Unit> {
        val puts = CopyOnWriteArrayList<String>()
        val http = HttpClient(MockEngine { req ->
            if (req.method.value == "PUT") puts += String(req.body.toByteArray())
            respond("", HttpStatusCode.NoContent)
        })
        val ch = UrbitChannel(http, "https://ship.test", "~zod")
        val started = System.currentTimeMillis()
        ch.subscribe("chat", "/v4")
        assertTrue(System.currentTimeMillis() - started < 1_000, "the first PUT waited")
        assertEquals(1, puts.size)
    }

    // Vere answers /~_~/healthz itself, no login and no event on the ship.
    @Test
    fun `health asks vere at its own path and reads the answer`() = runBlocking<Unit> {
        val asked = CopyOnWriteArrayList<String>()
        var status = HttpStatusCode.TooManyRequests
        val http = HttpClient(MockEngine { req -> asked += "${req.method.value} ${req.url}"; respond("", status) }) {
            install(io.ktor.client.plugins.HttpTimeout)
        }
        val ch = UrbitChannel(http, "https://ship.test/", "~zod")
        assertEquals(ShipHealth.BUSY, ch.health())
        assertEquals(listOf("GET https://ship.test/~_~/healthz"), asked.toList())
        status = HttpStatusCode.NoContent
        assertEquals(ShipHealth.IDLE, ch.health())
        val down = UrbitChannel(HttpClient(MockEngine { throw java.net.ConnectException("refused") }) { install(io.ktor.client.plugins.HttpTimeout) }, "https://ship.test", "~zod")
        assertEquals(ShipHealth.UNREACHABLE, down.health())
    }
}
