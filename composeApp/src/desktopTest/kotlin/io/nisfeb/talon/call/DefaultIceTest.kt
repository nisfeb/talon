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
 * The build's own STUN/TURN go to a ship only when it says it has
 * none. A failed read, or an answer in another shape, is not "none":
 * taking it for one replaced servers someone had set, on every device.
 */
class DefaultIceTest {
    private fun connect(ice: String?): FakeShip {
        val ship = FakeShip("~zod").apply {
            scries["trunk/version"] = """{"wire":9}"""
            scries["trunk/policy"] = "{}"
            if (ice != null) scries["trunk/ice"] = ice
        }
        val calls = CallController(
            UrbitSession(ship.http, ship.session).apply { tryRestore("~zod") },
            CallEngineProvider { error("no media") },
            defaults = CallDefaults(iceSpec = "stun:stun.talon.test:3478"),
        ).apply { start() }
        try {
            runBlocking {
                withTimeout(10_000) { while (!calls.connected.value) delay(20) }
                // What the connect does with the answer happens before it
                // reads the policy; give the poke, if any, time to go.
                withTimeout(10_000) { while ("trunk/policy" !in ship.scried) delay(20) }
                delay(300)
            }
        } finally {
            calls.stop()
        }
        return ship
    }

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
}
