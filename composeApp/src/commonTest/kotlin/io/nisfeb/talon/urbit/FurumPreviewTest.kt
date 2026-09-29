package io.nisfeb.talon.urbit

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The card for a furum link, from the reader's own furum's
 * GET /apps/furum/preview (see the contract in [FurumPreview]).
 */
class FurumPreviewTest {
    private val json = headersOf("Content-Type", "application/json")
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    private val postAnswer = """{"board":{"host":"~zod","name":"cats","title":"Cats","description":"All cats"},
        "post":{"id":42,"title":"A cat sat","author":"~bus","url":null,"excerpt":"on the mat","points":12,"comments":1}}"""

    /** A ship answering [replies] in turn, recording what it was asked. */
    private fun ship(asked: MutableList<String>, vararg replies: Pair<HttpStatusCode, String>): HttpClient {
        var n = 0
        return HttpClient(MockEngine { req ->
            asked += "${req.method.value} ${req.url.encodedPathAndQuery} ${req.headers["Cookie"]}"
            val (status, body) = replies[minOf(n++, replies.size - 1)]
            respond(body, status, json)
        })
    }

    @Test
    fun `a post's card says where, how it stands, and how it starts`() {
        val card = FurumPreview.cardOf(FurumRef("~zod", "cats", 42), obj(postAnswer))!!
        assertEquals("f/~zod/cats · 12 points · 1 comment", card.caption)
        assertEquals("A cat sat", card.title)
        assertEquals("on the mat", card.snippet)
        val bare = FurumPreview.cardOf(FurumRef("~zod", "cats", 42), obj("""{"post":{"title":"T","author":"~bus","points":1}}"""))!!
        assertEquals("f/~zod/cats · 1 point", bare.caption)
        assertEquals("by ~bus", bare.snippet)
    }

    @Test
    fun `a board's card is its title and description, and too little is none`() {
        val card = FurumPreview.cardOf(FurumRef("~zod", "cats"), obj(postAnswer))!!
        assertEquals(FurumPreview.Card("f/~zod/cats", "Cats", "All cats"), card)
        assertNull(FurumPreview.cardOf(FurumRef("~zod", "cats"), obj("""{"board":{}}""")))
        assertNull(FurumPreview.cardOf(FurumRef("~zod", "cats", 1), obj("""{"board":{"title":"Cats"}}""")))
    }

    @Test
    fun `the reader's ship is asked once, with its cookie, and the answer is kept`() = runTest {
        FurumPreview.clear()
        val asked = mutableListOf<String>()
        val http = ship(asked, HttpStatusCode.OK to postAnswer)
        val ref = FurumRef("~zod", "cats", 42)
        assertEquals("A cat sat", FurumPreview.await(http, "https://me.example", "session=x", ref)?.title)
        assertEquals("A cat sat", FurumPreview.await(http, "https://me.example", "session=x", ref)?.title)
        assertEquals(listOf("GET /apps/furum/preview?board=~zod/cats&post=42 session=x"), asked)
    }

    @Test
    fun `a ship still asking the host is asked once more, a little later`() = runTest {
        FurumPreview.clear()
        val asked = mutableListOf<String>()
        val waits = mutableListOf<Long>()
        val http = ship(asked, HttpStatusCode.Accepted to """{"pending":true}""", HttpStatusCode.OK to postAnswer)
        val card = FurumPreview.await(http, "https://me.example", "c=1", FurumRef("~zod", "cats", 43), wait = { waits += it })
        assertEquals("A cat sat", card?.title)
        assertEquals(2, asked.size)
        assertEquals(listOf(5_000L), waits)
    }

    @Test
    fun `no furum, no route or no such board is no card, and is not asked again`() = runTest {
        FurumPreview.clear()
        val asked = mutableListOf<String>()
        val http = ship(asked, HttpStatusCode.NotFound to """{"error":{"message":"no such route"}}""")
        val ref = FurumRef("~zod", "gone")
        assertNull(FurumPreview.await(http, "https://me.example", "c=1", ref))
        assertNull(FurumPreview.await(http, "https://me.example", "c=1", ref))
        assertEquals(1, asked.size)
    }

    @Test
    fun `a ship that did not answer is not an answer, and is asked again next time`() = runTest {
        FurumPreview.clear()
        val asked = mutableListOf<String>()
        val http = ship(asked, HttpStatusCode.BadGateway to "", HttpStatusCode.Accepted to "{}")
        val ref = FurumRef("~zod", "flaky")
        assertNull(FurumPreview.await(http, "https://me.example", "c=1", ref))
        assertNull(FurumPreview.await(http, "https://me.example", "c=1", ref, wait = {}))
        assertEquals(3, asked.size, "a 502, then a 202 asked twice")
    }
}
