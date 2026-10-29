package io.nisfeb.talon.orrery

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Orrery 130's evening routes, as its app.hoon reads them: +serve-due
 * (POST /api/actions/<id>/due, its body through +due-input), the rhythm's
 * review_at (+de-rhythm: blank is off, absent is 20:00; the PUT merges,
 * +do-set-merged), and the week's review (GET /api/review/last).
 */
class OrreryEveningWireTest {
    private val sent = mutableListOf<HttpRequestData>()
    private val owned = mutableListOf<HttpRequestData>()

    private fun api(status: HttpStatusCode = HttpStatusCode.OK, body: String = "{}"): OrreryApi {
        fun engine(into: MutableList<HttpRequestData>) = MockEngine { req -> into += req; respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }
        return OrreryApi(owner = HttpClient(engine(owned)), bare = HttpClient(engine(sent)), baseUrl = "https://ship/")
    }

    private fun HttpRequestData.text() = (body as TextContent).text

    @Test
    fun `a due goes up as a day, a time or null, and the ship's due is read back`() = runTest {
        // +due-input: a 10-byte cord is a day (T00:00:00Z added), any other an ISO time, null none.
        assertEquals("2026-10-30T00:00:00Z", api(body = """{"ok":true,"id":"a1","due":"2026-10-30T00:00:00Z"}""").due(null, "a1", "2026-10-30"))
        assertEquals("https://ship/apps/orrery/api/actions/a1/due", owned.last().url.toString())
        assertEquals(HttpMethod.Post, owned.last().method)
        assertEquals("""{"due":"2026-10-30"}""", owned.last().text())

        api(body = """{"ok":true,"id":"a1","due":"2026-10-30T02:00:00Z"}""").due(null, "a1", "2026-10-30T02:00:00Z")
        assertEquals("""{"due":"2026-10-30T02:00:00Z"}""", owned.last().text())

        assertNull(api(body = """{"ok":true,"id":"a1","due":null}""").due(null, "a1", null))
        assertEquals("""{"due":null}""", owned.last().text(), "null, not left out: +serve-due 400s a body with no due")
    }

    @Test
    fun `a due moved with a key goes on the key, not the cookie`() = runTest {
        api(body = """{"ok":true,"id":"a1","due":null}""").due("k1.secret", "a1", null)
        assertEquals("Bearer k1.secret", sent.single().headers[HttpHeaders.Authorization])
        assertEquals(emptyList(), owned)
    }

    @Test
    fun `an action not open, or gone, is refused with its status`() = runTest {
        // The calendar falls back to its own edit on these two, so the status must come through.
        for (code in listOf(409, 404)) {
            val e = assertFailsWith<OrreryError.Refused> { api(HttpStatusCode.fromValue(code), """{"error":"only an open action is moved"}""").due(null, "a1", null) }
            assertEquals(code, e.status)
        }
    }

    @Test
    fun `the reminder time is the rhythm's review_at, blank for off, and set alone`() = runTest {
        assertEquals("20:00", api(body = """{"quiet_from":"21:00","review_at":"20:00"}""").reviewAt())
        assertEquals("https://ship/apps/orrery/api/rhythm", owned.last().url.toString())
        assertEquals("", api(body = """{"quiet_from":"21:00","review_at":""}""").reviewAt())

        api().setReviewAt("21:30")
        assertEquals(HttpMethod.Put, owned.last().method)
        assertEquals("https://ship/apps/orrery/api/rhythm", owned.last().url.toString())
        // Only review_at: the PUT merges, so quiet hours and the rest are kept.
        assertEquals("""{"review_at":"21:30"}""", owned.last().text())
        api().setReviewAt("")
        assertEquals("""{"review_at":""}""", owned.last().text())
    }

    @Test
    fun `the week's review is the owner's, read as a brief, and none before the first`() = runTest {
        val r = api(body = """{"day":"2026-10-25","at":"2026-10-25T22:00:00Z","sent":false,"text":"The week\n\nWork ran long.","line":"","notes":[]}""").reviewLast()
        assertEquals("https://ship/apps/orrery/api/review/last", owned.last().url.toString())
        assertEquals(Brief("2026-10-25", "2026-10-25T22:00:00Z", false, emptyMap(), "The week\n\nWork ran long."), r)
        assertNull(api(body = "{}").reviewLast())
    }
}
