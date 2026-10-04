package io.nisfeb.talon.urbit

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.toList
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
}
