package io.nisfeb.talon.call

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wire 5 through the controller: the role actions land on the channel
 * as trunk-action pokes with the exact shape the agent's ot casts,
 * and %access-state facts feed [CallController.roomAccess].
 */
class RoomAccessControllerTest {

    private fun controller(h: TrunkHarness) = CallController(
        h.session,
        CallEngineProvider { error("no media needed for role actions") },
    )

    @Test
    fun setRoomAccessPokesTheActionShape() = runBlocking<Unit> {
        val h = TrunkHarness()
        val c = controller(h)
        try {
            c.start()
            h.awaitConnected()
            c.setRoomAccess("~zod", "lounge", joinRoles = listOf("crew"), speakRoles = null)
            val put = h.awaitPut { it.contains("set-room-access") }
            val msg = Json.parseToJsonElement(put).jsonArray
                .map { it.jsonObject }
                .first { it["json"]?.jsonObject?.containsKey("set-room-access") == true }
            assertEquals("poke", msg["action"]!!.jsonPrimitive.content)
            assertEquals(TrunkWire.AGENT, msg["app"]!!.jsonPrimitive.content)
            assertEquals(TrunkWire.ACTION_MARK, msg["mark"]!!.jsonPrimitive.content)
            val body = msg["json"]!!.jsonObject["set-room-access"]!!.jsonObject
            assertEquals(setOf("host", "name", "join", "speak"), body.keys)
            assertEquals("~zod", body["host"]!!.jsonPrimitive.content)
            assertEquals("lounge", body["name"]!!.jsonPrimitive.content)
            assertEquals(
                listOf("crew"),
                body["join"]!!.jsonArray.map { it.jsonPrimitive.content },
            )
            assertTrue(body["speak"] is JsonNull, "everyone-may-speak is JSON null, not absent")
        } finally {
            c.stop()
        }
    }

    @Test
    fun anAccessStateFactPopulatesRoomAccess() = runBlocking<Unit> {
        val h = TrunkHarness()
        val c = controller(h)
        try {
            c.start()
            h.awaitConnected()
            h.emitFact(
                """{"access-state":{"from":"~zod","name":"lounge",""" +
                    """"join":null,"speak":["crew","officers"],"muted":["~bus"]}}""",
            )
            h.await { c.roomAccess.value.containsKey("~zod/lounge") }
            assertEquals(
                RoomAccess(null, listOf("crew", "officers"), listOf("~bus")),
                c.roomAccess.value["~zod/lounge"],
            )
        } finally {
            c.stop()
        }
    }
}
