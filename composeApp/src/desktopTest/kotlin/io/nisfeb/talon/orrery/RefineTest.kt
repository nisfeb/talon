package io.nisfeb.talon.orrery

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Rule 17: what the owner types under a proposal goes to the ship, and
 * the ship answers with the action revised in place, whatever else the
 * text asked for beside it, and a note where it would not. The window
 * redraws from that answer and never from what it sent, since the ship
 * may have resolved a name loosely spelled or refused outright.
 */
class RefineTest {
    private var sent: String? = null

    private fun api(answer: String) = OrreryApi(
        HttpClient(MockEngine { respond("{}") }),
        HttpClient(
            MockEngine { req ->
                sent = (req.body as? TextContent)?.text
                respond(answer, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ),
        "https://ship",
    )

    @Test
    fun `a refinement answers with the action the ship now holds`() = runTest {
        val r = api(
            """{"ok": true, "note": "",
                "action": {"id":"a1","kind":"message","title":"Tell Rose and Susan","status":"proposed","by":"generator",
                           "about":["person/rose","person/susan-egan"],
                           "payload":{"via":"chat","to":"person/rose","text":"on my way"}},
                "extras": [{"id":"a2","kind":"task","title":"Go shopping","status":"proposed","by":"generator","about":["a1"],"payload":{}}]}""",
        ).refine("k", "a1", "include susan egan in this")

        assertEquals("Tell Rose and Susan", r.action?.title)
        assertEquals("proposed", r.action?.status, "it stays proposed: approving is the same tap it was")
        assertEquals(listOf("person/rose", "person/susan-egan"), r.action?.about)
        assertEquals(listOf("a2"), r.extras.map { it.id }, "what the text asked for beyond the action is its own proposal")
        assertEquals(
            "include susan egan in this",
            Json.parseToJsonElement(sent!!).jsonObject["text"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `a refusal changes nothing and says why`() = runTest {
        val r = api("""{"ok": false, "note": "no person named susan egan on the ship"}""")
            .refine("k", "a1", "include susan egan in this")
        assertNull(r.action, "nothing to redraw from: the action is as it was")
        assertTrue(r.extras.isEmpty())
        assertEquals("no person named susan egan on the ship", r.note)
    }
}
