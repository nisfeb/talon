package io.nisfeb.talon.orrery

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A message action, rules 11 and 14: the payload read, the address the person's own, the claim confirmed. */
class MessageActionTest {
    private val state = Json.parseToJsonElement(
        """{"bodies": [
            {"id": "person/rose", "name": "Rose", "attrs": {"ship": {"value": "sampel-palnet"}, "email": {"value": "rose@example.com"}}},
            {"id": "person/sam", "name": "Sam", "ship": "~dozzod-sampel", "attrs": {}},
            {"id": "person/andrea", "name": "Andrea", "attrs": {"email": {"value": "not an address"}}}]}""",
    ).jsonObject

    @Test
    fun `the address is the person's own attribute, never the body id`() {
        assertEquals("~sampel-palnet", addressOf(state, "person/rose", "chat"))
        assertEquals("rose@example.com", addressOf(state, "person/rose", "mail"))
        assertEquals("~dozzod-sampel", addressOf(state, "person/sam", "chat"), "the ship the view gives the body")
        assertNull(addressOf(state, "person/sam", "mail"))
        assertNull(addressOf(state, "person/andrea", "mail"), "an email that is not one")
        assertNull(addressOf(state, "person/andrea", "chat"), "no ship, and none guessed from the id")
        assertNull(addressOf(state, "person/ghost", "chat"))
    }

    @Test
    fun `a message action reads the schema's via, to and text`() {
        fun action(vararg p: Pair<String, String>) = OrreryAction("m", "message", "Tell Rose", buildJsonObject { p.forEach { (k, v) -> put(k, v) } }, listOf("person/rose"), null, "approved", "generator")
        assertEquals(MessageToSend("chat", "person/rose", "late"), action("via" to "Chat", "to" to "person/rose", "text" to " late ").messageToSend())
        assertNull(action("to" to "person/rose", "text" to "late").messageToSend(), "no channel, no guess")
        assertNull(action("via" to "chat", "text" to "late").messageToSend(), "no one to send it to")
        assertEquals(setOf("chat", "mail"), TALON_CHANNELS)
    }

    private fun api(claimedBy: String?, answerBy: String = "talon-desktop") = OrreryApi(
        HttpClient(MockEngine { respond("{}") }),
        HttpClient(MockEngine { req ->
            val body = when {
                req.method == HttpMethod.Post -> """{"id": "a1", "status": "claimed", "by": "$answerBy"}"""
                claimedBy == null -> "[]"
                else -> """[{"id": "a1", "status": "claimed", "history": [
                    {"status": "claimed", "by": "telegram"}, {"status": "approved", "by": "owner"}, {"status": "claimed", "by": "$claimedBy"}]}]"""
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }),
        "https://ship",
    )

    @Test
    fun `a claim is ours only when the ship's last claimed step says so`() = runTest {
        assertNull(api("talon-desktop").claim("k", "a1"))
        assertEquals("claimed by telegram", api("telegram").claim("k", "a1"))
        assertEquals("the claim did not land in 5 reads", api(null).claim("k", "a1"))
    }

    @Test
    fun `a payload is held to its shape`() {
        val shape = Json.parseToJsonElement("""{"via": "required: one of telegram, mail, chat; the channel", "to": "required: a body id", "text": "required"}""").jsonObject
        val ok = buildJsonObject { put("via", "MAIL"); put("to", "Person/Rose"); put("text", "hi") }
        assertEquals(buildJsonObject { put("via", "mail"); put("to", "person/rose"); put("text", "hi") }, checkPayload(ok, shape, setOf("person/rose")).first)
        assertEquals("lacks text", checkPayload(JsonObject(ok - "text"), shape, setOf("person/rose")).second)
        assertEquals("to names no body the ship has: person/rose", checkPayload(ok, shape, emptySet()).second)
    }
}
