package io.nisfeb.talon.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The wire half of the vendor's ZDR tier: a call [forFeature] marks
 * zdrOnly carries OpenRouter's provider.zdr, which routes it only to
 * endpoints that keep nothing (or refuses), and a call not marked
 * carries no provider object at all. Pinned for both chat clients:
 * catch-up runs on [AiClient], the assistant on [AgentClient].
 */
class ZdrTierWireTest {
    private var sent: JsonObject? = null

    private fun http() = HttpClient(MockEngine { req ->
        sent = Json.parseToJsonElement((req.body as TextContent).text).jsonObject
        respond(
            """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}""",
            HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
        )
    })

    // an Armillary lease: OpenRouter's base, reached as a server of the OpenAI shape
    private fun cfg(zdr: Boolean) = AiSettings.Config(
        provider = AiSettings.Provider.Custom, apiKey = "sk-or-lease", model = "z/1",
        baseUrl = "https://openrouter.ai/api/v1", usageInclude = true, zdrOnly = zdr,
    )

    @Test
    fun `catch-up on the vendor's ZDR tier asks for ZDR endpoints only`() = runTest {
        AiClient(http = http()) { cfg(true) }.complete("system", "user")
        assertEquals(true, sent!!["provider"]!!.jsonObject["zdr"]!!.jsonPrimitive.boolean)
        AiClient(http = http()) { cfg(false) }.complete("system", "user")
        assertNull(sent!!["provider"], "a call not on the ZDR tier says nothing of it")
    }

    @Test
    fun `the assistant on the vendor's ZDR tier asks the same`() = runTest {
        AgentClient(http = http()) { cfg(true) }.completeWithTools("system", listOf(AgentMessage.User("hi")), emptyList())
        assertEquals(true, sent!!["provider"]!!.jsonObject["zdr"]!!.jsonPrimitive.boolean)
        AgentClient(http = http()) { cfg(false) }.completeWithTools("system", listOf(AgentMessage.User("hi")), emptyList())
        assertNull(sent!!["provider"])
    }
}
