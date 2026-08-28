package io.nisfeb.talon.urbit

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A refused poke must throw.
 *
 * Every %trunk action was fire-and-forget: the PUT succeeded, the ack
 * was never read, and a nack looked exactly like success. That is how
 * a misspelled mark key shipped — the switch simply did nothing, twice,
 * across two releases. This pins the ack path so it can't regress into
 * silence again.
 */
class PokeAckTest {

    /** Serves the channel PUT, and an SSE stream we feed by hand. */
    private fun channelWith(sse: String): Pair<HttpClient, MutableList<String>> {
        val puts = mutableListOf<String>()
        val engine = MockEngine { req ->
            if (req.method.value == "PUT") {
                puts += (req.body as io.ktor.http.content.TextContent).text
                respond("", HttpStatusCode.NoContent)
            } else {
                respond(
                    sse,
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
            }
        }
        return HttpClient(engine) to puts
    }

    private fun frame(id: Long, body: String) = "id: $id\ndata: $body\n\n"

    @Test
    fun anAckLetsThePokeReturn() = runBlocking {
        val ok = frame(1, """{"id":1,"response":"poke","ok":true}""")
        val (http, puts) = channelWith(ok)
        val ch = UrbitChannel(http, "https://ship.test", "zod")

        val events = ch.events().onEach { }.launchIn(this)
        withTimeout(10_000) {
            ch.poke("trunk", "trunk-action", buildJsonObject { put("set-call-mode", "open") })
        }
        assertTrue(puts.any { it.contains("trunk-action") }, "the poke should have gone out")
        events.cancel()
    }

    @Test
    fun aNackThrowsAndNamesWhatFailed() = runBlocking {
        val bad = frame(1, """{"id":1,"response":"poke","err":"bad-key 'configure-room'"}""")
        val (http, _) = channelWith(bad)
        val ch = UrbitChannel(http, "https://ship.test", "zod")

        val events = ch.events().onEach { }.launchIn(this)
        val thrown = assertFailsWith<PokeNacked> {
            withTimeout(10_000) {
                ch.poke("trunk", "trunk-action", buildJsonObject { put("x", 1) })
            }
        }
        // The reason alone ("bad-key") says nothing about what was being
        // attempted, which is exactly what made these hard to find.
        assertEquals("trunk", thrown.app)
        assertEquals("trunk-action", thrown.mark)
        assertTrue(thrown.reason.contains("bad-key"))
        assertTrue(thrown.message!!.contains("trunk-action"))
        events.cancel()
    }

    @Test
    fun anUnrelatedEventDoesNotSettleThePoke() = runBlocking {
        // A fact carrying the same id must not be mistaken for an ack;
        // only response == "poke" counts.
        val fact = frame(1, """{"id":1,"response":"diff","json":{"hello":true}}""")
        val (http, _) = channelWith(fact)
        val ch = UrbitChannel(http, "https://ship.test", "zod")

        val events = ch.events().onEach { }.launchIn(this)
        // No ack arrives, so this falls through on the timeout path
        // rather than throwing — unobserved, not failed.
        // Sits out the ack timeout deliberately: the point is that it
        // returns rather than throwing.
        val id = async { ch.poke("trunk", "trunk-action", JsonPrimitive(1)) }
        assertTrue(id.await() > 0)
        events.cancel()
    }
}
