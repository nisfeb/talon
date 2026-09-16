package io.nisfeb.talon.mail

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The auspex client against a mock ship. What is worth pinning here is
 * the shape of what we send, the safety of how we read what comes back,
 * and that the three ways a call can fail stay apart.
 */
class AuspexApiTest {

    private val seen = mutableListOf<HttpRequestData>()

    private fun api(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): AuspexApi {
        val http = HttpClient(MockEngine { req -> seen += req; handler(req) })
        return AuspexApi(http, "https://ship.example")
    }

    private fun jsonOk(scope: MockRequestHandleScope, body: String) =
        scope.respond(
            ByteReadChannel(body),
            HttpStatusCode.OK,
            headersOf("Content-Type", "application/json"),
        )

    private fun sentBody(): JsonObject =
        Json.parseToJsonElement(
            (seen.last().body as io.ktor.http.content.TextContent).text,
        ).jsonObject

    // ---- reads ---------------------------------------------------------

    @Test
    fun `whoami reads the ship off the nexus`() = runBlocking<Unit> {
        val a = api { jsonOk(this, """{"ship":"~sampel-palnet"}""") }
        assertEquals("~sampel-palnet", a.whoami())
        assertEquals(
            "https://ship.example/apps/auspex/api/whoami",
            seen.last().url.toString(),
        )
    }

    @Test
    fun `a listing carries its row verdicts`() = runBlocking<Unit> {
        val a = api {
            jsonOk(
                this,
                """{"total":2,"offset":0,"limit":50,"view":"inbox","threads":[
                   {"id":"0v1","subject":"hi","from":"~zod","snippet":"there",
                    "verdict":"verified","forged":false,"count":1,"last":100,
                    "unread":true,"participants":["~zod"],"unreadable":0,
                    "archived":false,"labels":[]},
                   {"id":"0v2","subject":"bad","from":"~nec","snippet":"",
                    "verdict":"forged","forged":true,"count":2,"last":90,
                    "unread":false,"participants":["~nec"],"unreadable":1,
                    "archived":false,"labels":["work"]}]}""",
            )
        }
        val page = a.inbox()
        assertEquals(2, page.total)
        assertEquals(Verdict.VERIFIED, page.threads[0].verdict)
        assertEquals(Verdict.FORGED, page.threads[1].verdict)
        assertTrue(page.threads[1].forged)
        assertEquals(1, page.threads[1].unreadable)
    }

    @Test
    fun `a listing asks for the view it was given`() = runBlocking<Unit> {
        val a = api { jsonOk(this, """{"threads":[]}""") }
        a.inbox(view = MailView.ARCHIVED, query = "a b", offset = 20, limit = 10)
        val url = seen.last().url.toString()
        assertTrue(url.contains("view=archived"), url)
        assertTrue(url.contains("q=a%20b") || url.contains("q=a+b"), url)
        assertTrue(url.contains("offset=20"), url)
        assertTrue(url.contains("limit=10"), url)
    }

    @Test
    fun `a message with no attachments field is a message with no attachments`() = runBlocking<Unit> {
        val a = api {
            jsonOk(
                this,
                """{"id":"0vt","messages":[{"id":"0vm","from":"~zod","to":["~nec"],
                   "subject":"s","body":"b","body-mime":"","sent":5,"prev":null,
                   "verdict":"verified","read":false}],
                   "participants":["~zod"],"last":5,"unreadable":0,
                   "archived":false,"labels":[]}""",
            )
        }
        val t = a.thread("0vt")!!
        assertEquals(emptyList(), t.messages[0].attachments)
        assertEquals("", t.messages[0].bodyMime)
        assertNull(t.messages[0].prev)
    }

    @Test
    fun `an unknown verdict is never read as verified`() = runBlocking<Unit> {
        val a = api {
            jsonOk(
                this,
                """{"id":"0vt","messages":[{"id":"0vm","from":"~zod",
                   "subject":"s","body":"b","sent":5,"prev":null,
                   "verdict":"something-new","read":false}]}""",
            )
        }
        assertEquals(Verdict.UNVERIFIED, a.thread("0vt")!!.messages[0].verdict)
    }

    @Test
    fun `a thread that is gone is null, not a failure`() = runBlocking<Unit> {
        val a = api { respondError(HttpStatusCode.NotFound, """{"error":"not found"}""") }
        assertNull(a.thread("0vgone"))
    }

    // ---- writes --------------------------------------------------------

    @Test
    fun `a compose sends its null parent rather than omitting it`() = runBlocking<Unit> {
        val a = api { jsonOk(this, """{"ok":true}""") }
        a.send(to = listOf("~zod"), subject = "s", body = "b", prev = null)
        val body = sentBody()
        assertTrue("prev" in body.keys, "the ship refuses a body missing a key: ${body.keys}")
        assertTrue(body["prev"] is kotlinx.serialization.json.JsonNull)
        assertEquals(listOf("~zod"), body["to"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("s", body["subject"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a reply names the message it answers`() = runBlocking<Unit> {
        val a = api { jsonOk(this, """{"ok":true}""") }
        a.send(to = listOf("~zod"), subject = "re", body = "b", prev = "0vparent")
        assertEquals("0vparent", sentBody()["prev"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an empty attachment list is left off entirely`() = runBlocking<Unit> {
        val a = api { jsonOk(this, """{"ok":true}""") }
        a.send(to = listOf("~zod"), subject = "s", body = "b")
        assertEquals(
            0,
            sentBody()["attachments"]!!.jsonArray.size,
            "present and empty is accepted; present and malformed is not",
        )
    }

    @Test
    fun `marking read sends one set, and nothing at all when empty`() = runBlocking<Unit> {
        val a = api { jsonOk(this, """{"ok":true}""") }
        a.markRead(emptyList())
        assertTrue(seen.isEmpty(), "an empty mark is a no-op, not a request")
        a.markRead(listOf("0va", "0vb"))
        assertEquals(1, seen.size)
        assertEquals(
            listOf("0va", "0vb"),
            sentBody()["msg-ids"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    // ---- the three failures --------------------------------------------

    @Test
    fun `a refusal carries the ship's own reason`() = runBlocking<Unit> {
        val a = api { respondError(HttpStatusCode.BadRequest, """{"error":"unknown attachment 0v9"}""") }
        val e = assertFailsWith<AuspexError.Refused> { a.whoami() }
        assertEquals(400, e.status)
        assertEquals("unknown attachment 0v9", e.reason)
    }

    @Test
    fun `a dead session is recognisable without parsing a message`() = runBlocking<Unit> {
        val a = api { respondError(HttpStatusCode.Forbidden, """{"error":"forbidden"}""") }
        val e = assertFailsWith<AuspexError.Refused> { a.whoami() }
        assertTrue(e.isSignedOut)
    }

    @Test
    fun `an answer we cannot read is not the same as no answer`() = runBlocking<Unit> {
        val a = api { jsonOk(this, "this is not json") }
        assertFailsWith<AuspexError.Garbled> { a.whoami() }
    }

    @Test
    fun `nothing coming back is its own failure`() = runBlocking<Unit> {
        val a = api { throw java.io.IOException("connection refused") }
        assertFailsWith<AuspexError.Unreachable> { a.whoami() }
    }
}
