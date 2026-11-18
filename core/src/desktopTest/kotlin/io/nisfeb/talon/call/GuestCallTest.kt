package io.nisfeb.talon.call

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * /trunk's call: a line of its own, an invite for people with no ship,
 * and the link handed on. Whatever fails, no line is left open, and the
 * reason is in words. Bodies are trunk's own (lib/trunk-json.hoon):
 * open-room `(ot ~[name+so title+so members+(ar ...) admins+(ar ...)])`
 * and invite-guests `(ot ~[name+so speak+bo ttl+ni uses+ni])`.
 */
class GuestCallTest {
    private fun harness(wire: Int = 19, server: Boolean = true, origin: String = "https://ship.example") = TrunkHarness().apply {
        scryBody = {
            when {
                it.endsWith("/version.json") -> """{"wire":$wire}"""
                it.endsWith("/sfu.json") -> if (server) """{"base":"https://sfu.example","configured":true}""" else """{"base":"","configured":false}"""
                it.endsWith("/guests.json") -> """{"invites":[],"links":[],"apps":[],"origin":"$origin"}"""
                else -> "{}"
            }
        }
    }

    private suspend fun started(h: TrunkHarness, wire: Int = 19): CallController {
        val c = CallController(h.session, CallEngineProvider { HangingCallEngine() })
        c.start()
        h.awaitConnected()
        h.await { c.wire.value == wire && c.policy.value != null }
        return c
    }

    private fun pokes(h: TrunkHarness): List<String> = h.putsSnapshot().flatMap { put ->
        Json.parseToJsonElement(put).jsonArray.mapNotNull { m ->
            m.jsonObject.takeIf { it["action"]?.jsonPrimitive?.content == "poke" }?.get("json")?.toString()
        }
    }

    private fun roomIn(poke: String) =
        Json.parseToJsonElement(poke).jsonObject.values.first().jsonObject["name"]!!.jsonPrimitive.content

    private suspend fun answer(h: TrunkHarness, uses: Int = 10): String {
        h.await { pokes(h).any { "invite-guests" in it } }
        val room = roomIn(pokes(h).first { "invite-guests" in it })
        h.emitFact("""{"guest-invite":{"code":"0123456789abcdef0123456789abcdef","name":"$room","path":"/apps/trunk/guest/0123456789abcdef0123456789abcdef","speak":true,"expires":1760140800,"uses":$uses,"guests":0}}""")
        return room
    }

    @Test
    fun `a line is opened, an invite asked for, and the link handed on`() = runBlocking<Unit> {
        val h = harness()
        val c = started(h)
        val got = async { var call: GuestCall? = null; c.guestCall("Standup", 5, 7200, speak = true) { call = it }; call!! }
        val room = answer(h, uses = 5)
        val call = got.await()
        assertTrue(Regex("call-[0-9a-f]{12}").matches(room), room)
        assertEquals(
            listOf(
                """{"open-room":{"name":"$room","title":"Standup","members":[],"admins":[]}}""",
                """{"invite-guests":{"name":"$room","speak":true,"ttl":7200,"uses":5}}""",
            ),
            pokes(h).filter { "open-room" in it || "invite-guests" in it || "close-room" in it },
        )
        assertEquals(GuestCall(room, "/apps/trunk/guest/0123456789abcdef0123456789abcdef", "https://ship.example", 1760140800, 5), call)
    }

    @Test
    fun `the link is still handed on when whoever asked has gone`() = runBlocking<Unit> {
        val h = harness()
        val c = started(h)
        var delivered: GuestCall? = null
        val asker = CoroutineScope(Dispatchers.Default).launch { c.guestCall("Call", 10, 3600, speak = true) { delivered = it } }
        h.await { pokes(h).any { "invite-guests" in it } }
        asker.cancelAndJoin()
        answer(h)
        h.await { delivered != null }
        assertTrue(pokes(h).none { "close-room" in it }, "the line stays: its link went out")
    }

    @Test
    fun `an older ship and a ship with no call server are refused before anything is opened`() = runBlocking<Unit> {
        val old = harness(wire = 10)
        val e = assertFailsWith<GuestCallRefused> { started(old, wire = 10).guestCall("Call", 10, 3600, true) {} }
        assertTrue("too old" in e.message!!, e.message)
        assertTrue(pokes(old).none { "open-room" in it })

        val bare = harness(server = false)
        val e2 = assertFailsWith<GuestCallRefused> { started(bare).guestCall("Call", 10, 3600, true) {} }
        assertTrue("no call server" in e2.message!!, e2.message)
        assertTrue(pokes(bare).none { "open-room" in it })
    }

    @Test
    fun `a refused invite closes the line it opened`() = runBlocking<Unit> {
        val h = harness()
        val c = started(h)
        h.onPut = { if ("invite-guests" in it) h.pokeErr = "nack" }
        val e = assertFailsWith<GuestCallRefused> { c.guestCall("Call", 10, 3600, true) { error("never") } }
        assertTrue("64" in e.message!!, e.message)
        val room = roomIn(pokes(h).first { "open-room" in it })
        h.await { pokes(h).any { it == """{"close-room":{"name":"$room"}}""" } }
    }

    @Test
    fun `a ship that never answers, and a link that could not be posted, leave no line`() = runBlocking<Unit> {
        val silent = harness()
        val e = assertFailsWith<GuestCallRefused> { started(silent).guestCall("Call", 10, 3600, true, answerTimeoutMs = 300) { error("never") } }
        assertTrue("did not answer" in e.message!!, e.message)
        assertTrue(pokes(silent).any { "close-room" in it })

        val h = harness()
        val c = started(h)
        val failed = async { runCatching { c.guestCall("Call", 10, 3600, true) { error("the chat refused the post") } }.exceptionOrNull() }
        answer(h)
        val e2 = failed.await()
        assertTrue(e2 is GuestCallRefused && "could not be posted" in e2.message!!, "$e2")
        assertTrue(pokes(h).any { "close-room" in it })
    }

    @Test
    fun `a ship with no public address set gives none`() = runBlocking<Unit> {
        val h = harness(origin = "")
        val c = started(h)
        val got = async { var call: GuestCall? = null; c.guestCall("Call", 10, 3600, true) { call = it }; call!! }
        answer(h)
        assertEquals("", got.await().origin)
    }
}
