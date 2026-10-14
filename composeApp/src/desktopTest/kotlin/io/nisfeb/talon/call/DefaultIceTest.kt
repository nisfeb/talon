package io.nisfeb.talon.call

import io.nisfeb.talon.urbit.FakeShip
import io.nisfeb.talon.urbit.UrbitSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The build's own STUN/TURN and sidecar go to a ship only when it says
 * it has none. A failed read, or an answer in another shape, is not
 * "none": taking it for one replaced servers someone had set, on every
 * device. And adopting them must not hold up the connect: the poke's
 * ack comes down the stream the connect has not started reading yet.
 */
class DefaultIceTest {
    private var sfuBase = ""

    private fun connect(
        ice: String?,
        sfu: String? = """{"configured":true,"base":"https://mine.test"}""",
        ship: FakeShip = FakeShip("~zod"),
    ): FakeShip {
        ship.apply {
            scries["trunk/version"] = """{"wire":9}"""
            scries["trunk/policy"] = "{}"
            if (ice != null) scries["trunk/ice"] = ice
            if (sfu != null) scries["trunk/sfu"] = sfu
        }
        val calls = CallController(
            UrbitSession(ship.http, ship.session).apply { tryRestore("~zod") },
            CallEngineProvider { error("no media") },
            defaults = CallDefaults(
                iceSpec = "stun:stun.talon.test:3478", sfuBase = "https://sfu.talon.test", sfuKey = "k",
                retiredSfuBase = "http://old.talon.test:8444",
            ),
        ).apply { start() }
        try {
            runBlocking {
                // Well inside the 15s a waited-for poke would have taken.
                withTimeout(10_000) { while (!calls.connected.value) delay(20) }
                // The adoptions go off the connect path; give the pokes, if any, time to go.
                delay(300)
                sfuBase = calls.shipSfuBase.value
            }
        } finally {
            calls.stop()
        }
        return ship
    }

    private fun FakeShip.setSfu() = pokesTo("trunk").filter { "set-sfu" in it.json.toString() }

    private fun FakeShip.setIce() = pokesTo("trunk").filter { "set-ice" in it.json.toString() }

    @Test
    fun `a ship that says it has no servers is given the build's`() {
        val sent = connect("[]").setIce()
        assertEquals(1, sent.size)
        assertTrue("stun.talon.test" in sent.single().json.toString())
    }

    @Test
    fun `a ship whose servers could not be read, or were set, keeps them`() {
        assertTrue(connect(ice = null).setIce().isEmpty(), "no answer is not no servers")
        assertTrue(connect("""{"unexpected":true}""").setIce().isEmpty(), "an answer in another shape is not either")
        assertTrue(connect("""[{"url":"turn:mine.example:3478","user":"u","cred":"c"}]""").setIce().isEmpty())
    }

    @Test
    fun `a ship that says it has no sidecar is given the build's, without holding the connect`() {
        val sent = connect("[]", sfu = """{"configured":false,"base":""}""").setSfu()
        assertEquals(1, sent.size)
        assertTrue("sfu.talon.test" in sent.single().json.toString())
        assertEquals("https://sfu.talon.test", sfuBase)
    }

    @Test
    fun `a ship whose sidecar could not be read, or was set, keeps it`() {
        assertTrue(connect("[]", sfu = null).setSfu().isEmpty(), "no answer is not no sidecar")
        assertEquals("", sfuBase)
        assertTrue(connect("[]", sfu = "[]").setSfu().isEmpty(), "an answer in another shape is not either")
        assertTrue(connect("[]").setSfu().isEmpty())
        assertEquals("https://mine.test", sfuBase)
    }

    // The default sidecar moved, and ships an earlier build pointed at the
    // old one had no party lines (2026-10-11).
    private val retired = """{"configured":true,"base":"http://old.talon.test:8444","group":"talon"}"""

    @Test
    fun `a ship still on the retired sidecar is moved to the build's, group and key with it`() {
        val sent = connect("[]", sfu = retired).setSfu().single().json.toString()
        assertTrue(""""base":"https://sfu.talon.test"""" in sent && """"group":"talon"""" in sent && """"key":"k"""" in sent, sent)
        assertEquals("https://sfu.talon.test", sfuBase)
    }

    // Idempotency: a ship already moved (its scry now returns the new base)
    // must not be poked again on the next connect, or every reconnect re-fires.
    @Test
    fun `a ship already on the new default sidecar is not moved again`() {
        val onNew = """{"configured":true,"base":"https://sfu.talon.test","group":"talon"}"""
        assertTrue(connect("[]", sfu = onNew).setSfu().isEmpty(), "a migrated ship does not re-fire")
        assertEquals("https://sfu.talon.test", sfuBase)
    }

    @Test
    fun `a sidecar of its own that looks like the old default is kept`() {
        assertTrue(connect("[]", sfu = """{"configured":true,"base":"http://10.0.0.2:8444","group":"talon"}""").setSfu().isEmpty())
        assertEquals("http://10.0.0.2:8444", sfuBase)
    }

    @Test
    fun `a refused move says the ship is still on the old sidecar`() {
        val ship = FakeShip("~zod").apply { refuse = { if ("set-sfu" in it.json.toString()) "no" else null } }
        connect("[]", sfu = retired, ship = ship)
        assertEquals("http://old.talon.test:8444", sfuBase)
    }

    @Test
    fun `servers on our old or new host are replaced with the build's, and the user's own are kept`() {
        val old = """[{"url":"stun:old.talon.test:3478","user":"","cred":""},{"url":"turn:sfu.talon.test:3478","user":"talon","cred":"p"}]"""
        val sent = connect(old).setIce()
        assertEquals(1, sent.size)
        assertTrue("stun.talon.test" in sent.single().json.toString() && "old.talon.test" !in sent.single().json.toString())
        val mixed = """[{"url":"turn:old.talon.test:3478","user":"talon","cred":"p"},{"url":"turn:mine.example:3478","user":"u","cred":"c"}]"""
        assertTrue(connect(mixed).setIce().isEmpty())
    }
}
