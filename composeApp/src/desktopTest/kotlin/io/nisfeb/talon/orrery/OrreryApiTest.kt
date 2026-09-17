package io.nisfeb.talon.orrery

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The wire half: what goes up, on which client, and how the answers read. */
class OrreryApiTest {
    private var seen: HttpRequestData? = null

    private fun api(status: HttpStatusCode = HttpStatusCode.OK, body: String = "{}"): OrreryApi {
        val engine = MockEngine { req -> seen = req; respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }
        return OrreryApi(owner = HttpClient(engine), bare = HttpClient(engine), baseUrl = "https://ship/")
    }

    @Test
    fun `the probe reads presence off the status`() = runTest {
        assertEquals(OrreryAvailability.PRESENT, api().probe())
        assertEquals(OrreryAvailability.MISSING, api(HttpStatusCode.NotFound).probe())
        assertEquals(OrreryAvailability.SIGNED_OUT, api(HttpStatusCode.Forbidden).probe())
        assertEquals("https://ship/apps/orrery/api/state?kind=none", seen!!.url.toString())
    }

    @Test
    fun `minting asks for the pipe's scope and keeps the token`() = runTest {
        val key = api(body = """{"id":"k1","name":"Talon on x","by":"talon/x","scope":{},"token":"k1.secret","made":"2026-09-17T00:00:00Z"}""")
            .mint("Talon on x", "talon/x")
        assertEquals(MintedKey("k1", "k1.secret"), key)
        val sent = Json.parseToJsonElement((seen!!.body as TextContent).text).jsonObject
        assertEquals("talon/x", sent["by"]!!.jsonPrimitive.content)
        val scope = sent["scope"]!!.jsonObject
        assertEquals(OrreryApi.KINDS, scope["kinds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(OrreryApi.ACTIONS, scope["actions"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("true", scope["write"]!!.jsonPrimitive.content)
    }

    @Test
    fun `observing carries the key and reads per-item answers`() = runTest {
        val answer = api(body = """{"bodies":[{"id":"person/bus","ok":true,"existing":false}],"observations":[{"id":"1-a","ok":true,"existing":true},{"ok":false,"error":"unknown subject thing/x"}]}""")
            .observe(buildJsonObject { }, token = "k1.secret")
        assertEquals("Bearer k1.secret", seen!!.headers[HttpHeaders.Authorization])
        assertNull(seen!!.headers[HttpHeaders.Cookie], "the key request carries no cookie")
        assertEquals(1, answer.refused.size)
        assertEquals("unknown subject thing/x", answer.refused.single().error)
        assertEquals(true, answer.observations[0].existing)
    }

    @Test
    fun `open actions read out, and a transition goes up with its note`() = runTest {
        val list = api(body = """[{"id":"1758-a","kind":"message","title":"Tell Sarah","payload":{"recipient":"~sampel-palnet","text":"on my way"},"about":["person/sarah"],"due":null,"by":"claude-code","proposed":"2026-09-17T00:00:00Z","status":"approved","note":"","history":[]}]""")
            .actions("k1.secret")
        val a = list.single()
        assertEquals("message", a.kind)
        assertEquals(MessageToSend("~sampel-palnet", "on my way"), a.messageToSend())
        assertEquals(listOf("person/sarah"), a.about)
        assertEquals("Bearer k1.secret", seen!!.headers[HttpHeaders.Authorization])
        api().transition("k1.secret", "1758-a", "failed", "the ship was down")
        assertEquals("https://ship/apps/orrery/api/actions/1758-a", seen!!.url.toString())
        val sent = Json.parseToJsonElement((seen!!.body as TextContent).text).jsonObject
        assertEquals("failed", sent["status"]!!.jsonPrimitive.content)
        assertEquals("the ship was down", sent["note"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a calendar action becomes an event with an hour by default`() {
        val a = OrreryAction("x", "calendar", "Dentist", kotlinx.serialization.json.buildJsonObject { put("start", kotlinx.serialization.json.JsonPrimitive("2026-09-18T09:00:00Z")) }, emptyList(), null, "approved", "claude-code")
        val e = a.eventToAdd()!!
        assertEquals("Dentist", e.title)
        assertEquals(e.startMs + 3_600_000, e.endMs)
        assertEquals(null, a.copy(kind = "task").eventToAdd())
    }

    @Test
    fun `a refusal names the ship's reason`() = runTest {
        val e = assertFailsWith<OrreryError.Refused> { api(HttpStatusCode.Forbidden, """{"error":"read only key"}""").observe(buildJsonObject { }, "t") }
        assertEquals(403, e.status)
        assertEquals("read only key", e.reason)
    }
}
