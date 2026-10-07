package io.nisfeb.talon.notify

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The wire against grubbery's handle-push-http (app/grubbery.hoon):
 * GET vapid-key answers the key as text; POST subscribe takes
 * {endpoint, p256dh, auth} as strings and answers {ok, sub_id}; POST
 * unsubscribe takes {sub_id}. Unauthenticated is a 403.
 */
class ShipPushApiTest {
    private val seen = mutableListOf<HttpRequestData>()
    private fun api(status: HttpStatusCode = HttpStatusCode.OK, body: String) =
        ShipPushApi(HttpClient(MockEngine { req -> seen += req; respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }), "https://ship/")

    @Test
    fun `the key is read as text`() = runTest {
        assertEquals("BDd3_hVL9fZi9Ybo2UUzA284WG5FZR30_95YeZJsiApwXKpNcF1rRPF3foIiBHXRdJI2Qhumhf6_LFTeZaNndIo", api(body = "BDd3_hVL9fZi9Ybo2UUzA284WG5FZR30_95YeZJsiApwXKpNcF1rRPF3foIiBHXRdJI2Qhumhf6_LFTeZaNndIo\n").vapidKey())
        assertEquals("https://ship/grubbery/push/vapid-key", seen.single().url.toString())
        assertEquals(HttpMethod.Get, seen.single().method)
    }

    @Test
    fun `subscribe sends the browser's shape and keeps the ship's id`() = runTest {
        val id = api(body = """{"ok":true,"sub_id":"0v1a.2b3c"}""").subscribe("https://ntfy.sh/upAbC123?up=1", "BPk", "aGVsbG8")
        assertEquals("0v1a.2b3c", id)
        val req = seen.single()
        assertEquals("https://ship/grubbery/push/subscribe", req.url.toString())
        assertEquals(HttpMethod.Post, req.method)
        val body = Json.parseToJsonElement((req.body as TextContent).text).jsonObject
        assertEquals(setOf("endpoint", "p256dh", "auth"), body.keys)
        assertEquals("https://ntfy.sh/upAbC123?up=1", body["endpoint"]!!.jsonPrimitive.content)
        assertEquals("BPk", body["p256dh"]!!.jsonPrimitive.content)
        assertEquals("aGVsbG8", body["auth"]!!.jsonPrimitive.content)
    }

    @Test
    fun `unsubscribe names the id`() = runTest {
        api(body = """{"ok":true}""").unsubscribe("0v1a.2b3c")
        assertEquals("https://ship/grubbery/push/unsubscribe", seen.single().url.toString())
        assertEquals("""{"sub_id":"0v1a.2b3c"}""", (seen.single().body as TextContent).text)
    }

    @Test
    fun `a refusal says its status`() = runTest {
        val e = assertFailsWith<ShipPushRefused> { api(HttpStatusCode.BadRequest, "\"invalid key length\"").subscribe("e", "p", "a") }
        assertEquals(400, e.status)
        assertFailsWith<ShipPushRefused> { api(HttpStatusCode.OK, """{"ok":true}""").subscribe("e", "p", "a") }
    }
}
