package io.nisfeb.talon.orrery

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.nisfeb.talon.ai.ModelHttpError
import io.nisfeb.talon.ai.isOutOfCredit
import io.nisfeb.talon.ai.paidSettings
import io.nisfeb.talon.ai.armillaryRow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The decision model through Armillary's proxy (armillary 34): the route
 * found from the base, the vendor's model on this device's key, and each
 * refusal said for what it is. Every failure leaves the reading model to
 * read, as the gate always has.
 */
class DecisionsRouteTest {
    private val asked = CopyOnWriteArrayList<Triple<String, String?, String>>()
    @Volatile private var reply: Pair<HttpStatusCode, String> =
        HttpStatusCode.OK to """{"answers":{"worth_reading":{"noul":0.9}},"usage":{"input_tokens":12,"cost":0.0001}}"""

    private val http = HttpClient(MockEngine { req ->
        asked += Triple(req.url.toString(), req.headers[HttpHeaders.Authorization], req.body.toByteArray().decodeToString())
        respond(reply.second, reply.first, headersOf(HttpHeaders.ContentType, "application/json"))
    })

    /** A proxied ship whose vendor names a decision model. */
    private val settings = paidSettings(
        row = armillaryRow(key = "k.s", base = "https://zod.example/apps/armillary/v1", suggested = mapOf("default" to "a/1", "decision" to "typesafe/jev-1.13")),
    ).let { c -> c.copy(savedProfile = c.savedProfile!!.copy(jev = true)) }

    private fun decider() = OpenRouterDecider(http, decisionKey(settings)!!, DecideSettings().under(settings).onVendor(settings))

    private val state = buildJsonObject { put("message", kotlinx.serialization.json.JsonPrimitive("hi")) }

    @Test
    fun `through the proxy it asks the vendor's route with the vendor's model and this device's key`() = runBlocking {
        val d = decider().ask(state, Gate.QUESTION)
        assertEquals(0.9, d.answers["worth_reading"]!!.jsonObject["noul"]!!.jsonPrimitive.content.toDouble())
        val (url, auth, body) = asked.single()
        assertEquals("https://zod.example/apps/armillary/alpha/decisions", url)
        assertEquals("Bearer k.s", auth)
        val sent = Json.parseToJsonElement(body).jsonObject
        assertEquals("typesafe/jev-1.13", sent["model"]!!.jsonPrimitive.content)
        assertTrue(sent["provider"]!!.jsonObject["zdr"]!!.jsonPrimitive.boolean)
        assertTrue("state" in sent && "questions" in sent)
    }

    @Test
    fun `not offered, so the reading model reads and the pass stops asking`() = runBlocking {
        reply = HttpStatusCode.NotFound to """{"error":{"message":"decisions: not offered"}}"""
        val dec = decider()
        val e = assertFailsWith<ModelHttpError> { dec.ask(state, Gate.QUESTION) }
        assertEquals(404, e.status)
        assertTrue(NOT_OFFERED in e.message!!)
        val g = Gate.decide(dec, 0.3, "hi", "~bus", emptyList(), emptyList())
        assertTrue(g.read, "a gate that cannot answer lets the message through to the reading model")
        assertEquals(1, asked.size, "after the vendor said no, nothing more is asked this pass")
    }

    @Test
    fun `an empty balance is said as every Armillary feature says it, and a 502 is a failed decision`() = runBlocking {
        reply = HttpStatusCode.PaymentRequired to """{"error":{"message":"balance: empty"}}"""
        val empty = assertFailsWith<ModelHttpError> { decider().ask(state, Gate.QUESTION) }
        assertEquals(402, empty.status)
        assertTrue(isOutOfCredit(empty.message), empty.message)

        reply = HttpStatusCode.BadGateway to """{"error":{"message":"decisions: upstream answered no cost"}}"""
        val dec = decider()
        val bad = assertFailsWith<ModelHttpError> { dec.ask(state, Gate.QUESTION) }
        assertEquals(502, bad.status)
        val g = Gate.decide(dec, 0.3, "hi", "~bus", emptyList(), emptyList())
        assertTrue(g.read && g.p == null, "failed, so read")
        assertEquals(3, asked.size, "a 502 does not stop the next ask")
    }
}
