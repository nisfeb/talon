package io.nisfeb.talon.orrery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A message action, rule 11: the payload read and held to its shape. */
class MessageActionTest {
    @Test
    fun `a message action reads the schema's via, to and text`() {
        fun action(vararg p: Pair<String, String>) = OrreryAction("m", "message", "Tell Rose", buildJsonObject { p.forEach { (k, v) -> put(k, v) } }, listOf("person/rose"), null, "approved", "generator")
        assertEquals(MessageToSend("chat", "person/rose", "late"), action("via" to "Chat", "to" to "person/rose", "text" to " late ").messageToSend())
        assertNull(action("to" to "person/rose", "text" to "late").messageToSend(), "no channel, no guess")
        assertNull(action("via" to "chat", "text" to "late").messageToSend(), "no one to send it to")
    }

    @Test
    fun `a payload is held to its shape`() {
        val shape = Json.parseToJsonElement("""{"via": "required: one of telegram, mail, chat; the channel", "to": "required: a body id", "text": "required"}""").jsonObject
        val ok = buildJsonObject { put("via", "MAIL"); put("to", "Person/Rose"); put("text", "hi") }
        assertEquals(buildJsonObject { put("via", "mail"); put("to", "person/rose"); put("text", "hi") }, checkPayload(ok, shape, setOf("person/rose")).first)
        assertEquals("lacks text", checkPayload(JsonObject(ok - "text"), shape, setOf("person/rose")).second)
        assertEquals("to names no body the ship has: person/rose", checkPayload(ok, shape, emptySet()).second)
    }

    // Orrery 60: four kinds the ship carries out itself once approved.
    @Test
    fun `a change the ship makes itself reads as that change`() {
        fun action(kind: String, p: String) = OrreryAction("c", kind, "", Json.parseToJsonElement(p).jsonObject, emptyList(), null, "proposed", "instruct")
        assertEquals("Not true: person/andrea's location is place/barcelona",
            action("correct", """{"subject":"person/andrea","attr":"location","value":{"ref":"place/barcelona"},"why":"she stayed home"}""").shipChange())
        assertEquals("True: person/sam's age is 41", action("fact", """{"subject":"person/sam","attr":"age","value":41}""").shipChange())
        assertEquals("person/samuel and person/sam are one, kept as person/sam", action("merge", """{"from":"person/samuel","into":"person/sam"}""").shipChange())
        assertEquals("From now on: never propose calls", action("preference", """{"text":"never propose calls"}""").shipChange())
        assertNull(action("task", """{"title":"x"}""").shipChange())
    }
}
